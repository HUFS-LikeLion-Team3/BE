package com.finsight.feedback.tools;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Operator-invoked ONLY: no Spring bean, HTTP controller, scheduler or background sync.
 *
 * check <file>       : validate immutable package JSON; never access a DB or AI server.
 * preflight <file>   : read-only DB collision/mapping check; no INSERTs and no AI calls.
 * register           : GET /internal/v1/package, validate+import+verify in local MySQL,
 *                      then POST verified inventory + receipt. Never posts before DB commit.
 *
 * Version identity: source-document UUID + immutable content SHA-256. The AI v1 package
 * contains no document_version and the current BE source_documents has no version column.
 * The importer must not invent a version or edit existing source/news records.
 */
public final class ApprovedPackageRegistrationCli {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ApprovedPackageRegistrationCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: check <package.json> | preflight <package.json> | register");
        }
        String mode = args[0];
        if ("register".equals(mode)) {
            if (args.length != 1) throw new IllegalArgumentException("register reads ONLY the live AI /package endpoint");
            registerFromAi();
            return;
        }
        if (!("check".equals(mode) || "preflight".equals(mode)) || args.length != 2) {
            throw new IllegalArgumentException("Usage: check <package.json> | preflight <package.json> | register");
        }
        PackageData pkg = validate(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        System.out.println("Package valid: " + pkg.id() + " documents=" + pkg.documents().size()
                + " chunks=" + pkg.documents().stream().mapToInt(d -> d.chunks().size()).sum());
        System.out.println("Required market UUIDs (must exist unchanged): " + pkg.marketIds());
        if ("preflight".equals(mode)) {
            try (Connection db = connect()) {
                assertSchema(db);
                checkDatabase(db, pkg);
                System.out.println("DB preflight OK: no conflicting document/chunk/market links");
            }
        }
    }

    private static void registerFromAi() throws Exception {
        Map<String, Object> response = object(JSON.readValue(aiRequest("GET", "/internal/v1/package", null), Map.class), "package response");
        Map<String, Object> packageJson = object(response.get("package"), "package");
        Map<String, Object> aiInventory = object(response.get("inventory"), "inventory");
        PackageData pkg = validate(JSON.writeValueAsString(packageJson));
        // The AI inventory must match the full immutable content before importing anything.
        if (!sameInventory(inventory(pkg), aiInventory)) throw conflict("AI package/inventory mismatch");

        try (Connection db = connect()) {
            assertSchema(db);
            verifyOperatorMarketMapping(db, pkg);
            db.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            db.setAutoCommit(false);
            try {
                checkDatabase(db, pkg);
                importMissing(db, pkg);
                Map<String, Object> verified = inventoryFromDb(db, pkg);
                if (!sameInventory(verified, aiInventory)) throw conflict("registration_inventory_mismatch: DB readback");
                db.commit();
            } catch (Exception ex) {
                db.rollback();
                throw ex;
            }
            // A separate fresh read after commit verifies durable DB state; failure means no receipt.
            db.setAutoCommit(true);
            Map<String, Object> durableInventory = inventoryFromDb(db, pkg);
            if (!sameInventory(durableInventory, aiInventory)) throw conflict("registration_inventory_mismatch: committed DB");
            // Stable identifier derived from the DB-verified inventory, not a copied AI receipt.
            String receipt = "be-issue26:" + sha256(canonical(durableInventory));
            // Forward the inventory in AI's original ordering, but ONLY after DB-derived verification.
            Map<String, Object> body = Map.of("registered", true, "inventory", aiInventory, "receipt", receipt);
            aiRequest("POST", "/internal/v1/package/registration", JSON.writeValueAsString(body));
            System.out.println("Registered " + pkg.id() + "; DB-verified receipt=" + receipt);
        }
    }

    public record Chunk(UUID id, String text, int order, String textHash) { }
    public record Document(UUID id, String title, String publisher, String url,
                           String sourceType, String selectionTier, Instant published,
                           String contentHash, List<Chunk> chunks, Set<UUID> targets) { }
    public record PackageData(String id, List<Document> documents, Set<UUID> marketIds) { }

    public static PackageData validate(String raw) throws Exception {
        Map<String, Object> packageRoot = object(JSON.readValue(raw, Map.class), "package");
        if (!"1".equals(text(packageRoot.get("schemaVersion"), "schemaVersion"))) throw conflict("unsupported schemaVersion");
        String kind = text(packageRoot.get("kind"), "kind");
        if (!Set.of("local_demo", "approved_export").contains(kind)) throw conflict("unsupported package kind");
        String packageId = text(packageRoot.get("packageId"), "packageId");
        if (!packageId.matches("sha256:[0-9a-f]{64}")) throw conflict("invalid packageId");
        Map<String, Object> withoutId = new HashMap<>(packageRoot);
        withoutId.remove("packageId");
        if (!("sha256:" + sha256(canonical(withoutId))).equals(packageId))
            throw conflict("packageId is not the canonical JSON SHA-256");
        List<?> sourceDocs = array(packageRoot.get("documents"), "documents");
        if (sourceDocs.isEmpty()) throw conflict("empty document package");
        List<Document> docs = new ArrayList<>();
        Set<UUID> docIds = new HashSet<>(), chunkIds = new HashSet<>(), allMarkets = new LinkedHashSet<>();
        Set<String> urlHashes = new HashSet<>();
        for (Object rawEntry : sourceDocs) {
            Map<String, Object> entry = object(rawEntry, "document entry");
            Map<String, Object> d = object(entry.get("document"), "document");
            if (!"approved".equals(text(d.get("approval_status"), "approval_status"))) throw conflict("unapproved source");
            if (d.containsKey("document_version")) throw conflict("document_version has no existing BE storage column");
            UUID id = uuid(d.get("id"), "document id");
            if (!docIds.add(id)) throw conflict("repeated document UUID " + id);
            String title = limited(d.get("title"), "title", 255), url = limited(entry.get("url"), "url", 255);
            String publisher = limited(entry.get("publisher"), "publisher", 255);
            String sourceType = text(entry.get("sourceType"), "sourceType");
            if (!Set.of("original_article", "official_statistic", "government_release", "central_bank_release", "research_report").contains(sourceType))
                throw conflict("unsupported sourceType " + sourceType);
            int tier = integer(d.get("selection_tier"), "selection_tier");
            String selectionTier = switch (tier) {
                case 0 -> "primary_official";
                case 1 -> "original_article";
                case 2 -> "trusted_research";
                default -> throw conflict("unconfirmed selection_tier mapping: " + tier);
            };
            if (tier == 0 && !(Set.of("official_statistic", "government_release", "central_bank_release").contains(sourceType)))
                throw conflict("primary_official tier with nonofficial source");
            if (tier == 1 && !"original_article".equals(sourceType)) throw conflict("original_article tier mismatch");
            if (tier == 2 && !"research_report".equals(sourceType)) throw conflict("trusted_research tier mismatch");
            String hash = text(d.get("content_hash"), "content_hash");
            if (!hash.matches("[0-9a-f]{64}")) throw conflict("invalid content_hash " + id);
            if (!urlHashes.add(url + "\0" + hash)) throw conflict("duplicate URL+hash in package");
            Instant published = OffsetDateTime.parse(text(d.get("published_at"), "published_at")).toInstant();
            if (!array(d.get("linked_news_ids"), "linked_news_ids").isEmpty())
                throw conflict("nonempty linked_news_ids cannot be mapped losslessly by v1 BE schema");
            Set<UUID> targets = new LinkedHashSet<>();
            for (Object target : array(d.get("market_target_ids"), "market_target_ids")) {
                UUID targetId = uuid(target, "market_target_ids");
                if (!targets.add(targetId)) throw conflict("duplicate target in document " + id);
            }
            allMarkets.addAll(targets);
            String content = stringExact(d.get("content"), "content");
            StringBuilder reconstructed = new StringBuilder();
            List<Chunk> chunks = new ArrayList<>();
            int previousEnd = 0;
            List<?> sourceChunks = array(d.get("stored_chunks"), "stored_chunks");
            if (sourceChunks.isEmpty()) throw conflict("document has no chunks: " + id);
            for (Object chunkObject : sourceChunks) {
                Map<String, Object> chunk = object(chunkObject, "stored_chunks");
                UUID chunkId = uuid(chunk.get("id"), "chunk id");
                if (!chunkIds.add(chunkId)) throw conflict("duplicate chunk UUID " + chunkId);
                int start = integer(chunk.get("start"), "start"), end = integer(chunk.get("end"), "end");
                int cps = content.codePointCount(0, content.length());
                if (start != previousEnd || end < start || end > cps) throw conflict("chunk offset gap/overlap for " + chunkId);
                String storedText = stringExact(chunk.get("text"), "chunk text");
                if (!storedText.equals(content.substring(content.offsetByCodePoints(0, start), content.offsetByCodePoints(0, end))))
                    throw conflict("chunk text differs from approved document: " + chunkId);
                if (storedText.getBytes(StandardCharsets.UTF_8).length > 65_535)
                    throw conflict("chunk too large for existing MySQL TEXT column: " + chunkId);
                previousEnd = end;
                reconstructed.append(storedText);
                chunks.add(new Chunk(chunkId, storedText, chunks.size(), sha256(storedText)));
            }
            if (!content.contentEquals(reconstructed) || !sha256(reconstructed.toString()).equals(hash))
                throw conflict("document SHA-256 mismatch: " + id);
            docs.add(new Document(id, title, publisher, url, sourceType, selectionTier,
                    published, hash, List.copyOf(chunks), Set.copyOf(targets)));
        }
        return new PackageData(packageId, List.copyOf(docs), Set.copyOf(allMarkets));
    }

    public static Map<String, Object> inventory(PackageData pkg) {
        List<Object> documents = new ArrayList<>();
        for (Document d : pkg.documents()) {
            List<Object> chunks = new ArrayList<>();
            for (Chunk c : d.chunks()) chunks.add(Map.of("chunkId", c.id().toString(), "textHash", c.textHash()));
            documents.add(Map.of("sourceDocumentId", d.id().toString(), "contentHash", d.contentHash(), "chunks", chunks));
        }
        return Map.of("packageId", pkg.id(), "documents", documents);
    }

    private static boolean sameInventory(Map<String, Object> left, Map<String, Object> right) {
        return flatInventory(left).equals(flatInventory(right));
    }

    /** Compare inventory entries by UUID, irrespective of JSON array order. Reject additions/omissions. */
    private static Map<String, String> flatInventory(Map<String, Object> inv) {
        if (!inv.keySet().equals(Set.of("packageId", "documents"))) throw conflict("unexpected inventory shape");
        Map<String, String> flat = new HashMap<>();
        flat.put("packageId", text(inv.get("packageId"), "inventory.packageId"));
        for (Object rawDoc : array(inv.get("documents"), "inventory.documents")) {
            Map<String, Object> doc = object(rawDoc, "inventory.document");
            if (!doc.keySet().equals(Set.of("sourceDocumentId", "contentHash", "chunks")))
                throw conflict("unexpected inventory document shape");
            String id = uuid(doc.get("sourceDocumentId"), "inventory.sourceDocumentId").toString();
            if (flat.putIfAbsent("doc:" + id, text(doc.get("contentHash"), "inventory.contentHash")) != null)
                throw conflict("duplicate inventory document UUID");
            for (Object rawChunk : array(doc.get("chunks"), "inventory.chunks")) {
                Map<String, Object> chunk = object(rawChunk, "inventory.chunk");
                if (!chunk.keySet().equals(Set.of("chunkId", "textHash")))
                    throw conflict("unexpected inventory chunk shape");
                String chunkId = uuid(chunk.get("chunkId"), "inventory.chunkId").toString();
                if (flat.putIfAbsent("chunk:" + id + ":" + chunkId,
                        text(chunk.get("textHash"), "inventory.textHash")) != null)
                    throw conflict("duplicate inventory chunk UUID");
            }
        }
        return flat;
    }

    private static Connection connect() throws Exception {
        String url = getenvRequired("DB_URL");
        if (!url.startsWith("jdbc:mysql:") || !url.contains("/finsight"))
            throw conflict("Refusing to access DB_URL not explicitly pointing at finsight");
        Connection db = DriverManager.getConnection(url, getenvRequired("DB_USERNAME"), getenvRequired("DB_PASSWORD"));
        try (var st = db.createStatement()) {
            st.execute("SET SESSION time_zone = '+00:00'");
            try (ResultSet r = st.executeQuery("SELECT DATABASE()")) {
                if (!r.next() || !"finsight".equals(r.getString(1))) throw conflict("Selected database is not finsight");
            }
        }
        return db;
    }

    private static void assertSchema(Connection db) throws SQLException {
        for (String table : List.of("source_documents", "source_document_chunks", "source_document_targets", "market_targets")) {
            try (PreparedStatement p = db.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                p.setString(1, table);
                try (ResultSet r = p.executeQuery()) { r.next(); if (r.getInt(1) != 1) throw conflict("Missing required BE table: " + table); }
            }
        }
    }

    /** Before a real registration, an operator must independently confirm each DB code↔UUID pair. */
    private static void verifyOperatorMarketMapping(Connection db, PackageData pkg) throws SQLException {
        String supplied = getenvRequired("ISSUE26_VERIFIED_MARKETS");
        Map<UUID, String> authorized = new HashMap<>();
        for (String pair : supplied.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) throw conflict("Use CODE=UUID pairs in ISSUE26_VERIFIED_MARKETS");
            UUID id = uuid(parts[1], "ISSUE26_VERIFIED_MARKETS");
            if (authorized.putIfAbsent(id, parts[0]) != null) throw conflict("duplicate independently verified UUID");
        }
        if (!authorized.keySet().containsAll(pkg.marketIds()))
            throw conflict("Operator has not confirmed all package market IDs: " + pkg.marketIds());
        for (UUID id : pkg.marketIds()) {
            try (PreparedStatement p = db.prepareStatement("SELECT code,is_active FROM market_targets WHERE id=UUID_TO_BIN(?)")) {
                p.setString(1, id.toString());
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || !authorized.get(id).equals(r.getString(1)) || !r.getBoolean(2))
                        throw conflict("BE market code/UUID not independently confirmed: " + id);
                }
            }
        }
    }

    private static void checkDatabase(Connection db, PackageData pkg) throws SQLException {
        for (UUID market : pkg.marketIds()) {
            try (PreparedStatement p = db.prepareStatement("SELECT is_active FROM market_targets WHERE id=UUID_TO_BIN(?)")) {
                p.setString(1, market.toString());
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw conflict("market_target_id not present in actual BE DB: " + market);
                    if (!r.getBoolean(1)) throw conflict("market_target_id not active: " + market);
                }
            }
        }
        for (Document d : pkg.documents()) {
            try (PreparedStatement p = db.prepareStatement("SELECT BIN_TO_UUID(id) FROM source_documents WHERE url=? AND content_hash=?")) {
                p.setString(1, d.url()); p.setString(2, d.contentHash());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) if (!d.id().toString().equals(r.getString(1)))
                        throw conflict("same URL + SHA-256 already stored with different document UUID: " + d.id());
                }
            }
            boolean exists = false;
            try (PreparedStatement p = db.prepareStatement("SELECT source_type,selection_tier,publisher,title,url,published_at,content_hash,BIN_TO_UUID(news_id),is_primary FROM source_documents WHERE id=UUID_TO_BIN(?)")) {
                p.setString(1, d.id().toString());
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        exists = true;
                        if (!d.sourceType().equals(r.getString(1)) || !d.selectionTier().equals(r.getString(2))
                                || !d.publisher().equals(r.getString(3)) || !d.title().equals(r.getString(4))
                                || !d.url().equals(r.getString(5))
                                || !d.published().equals(r.getTimestamp(6, UTC).toInstant())
                                || !d.contentHash().equals(r.getString(7)) || r.getString(8) != null || r.getBoolean(9))
                            throw conflict("immutable document metadata or news/primary flag conflict: " + d.id());
                    }
                }
            }
            if (!exists) {
                try (PreparedStatement p = db.prepareStatement("SELECT BIN_TO_UUID(source_document_id),content,chunk_order FROM source_document_chunks WHERE id=UUID_TO_BIN(?)")) {
                    for (Chunk c : d.chunks()) {
                        p.setString(1, c.id().toString());
                        try (ResultSet r = p.executeQuery()) { if (r.next()) throw conflict("chunk UUID already belongs to another document " + c.id()); }
                    }
                }
                continue;
            }
            Map<UUID, Chunk> existing = readChunks(db, d.id());
            if (existing.size() != d.chunks().size()) throw conflict("existing chunk count mismatch: " + d.id());
            for (Chunk c : d.chunks()) {
                Chunk stored = existing.get(c.id());
                if (stored == null || stored.order() != c.order() || !stored.text().equals(c.text())
                        || !stored.textHash().equals(c.textHash()))
                    throw conflict("existing chunk content/order/hash mismatch: " + c.id());
            }
            if (!readTargets(db, d.id()).equals(d.targets())) throw conflict("existing market links mismatch: " + d.id());
        }
    }

    private static void importMissing(Connection db, PackageData pkg) throws SQLException {
        Instant importedAt = Instant.now(); // NOTE: retrieved_at is BE registration time, NOT original collection time.
        for (Document d : pkg.documents()) {
            if (documentExists(db, d.id())) continue; // validated immutable exact match above
            try (PreparedStatement p = db.prepareStatement("""
                    INSERT INTO source_documents (id,news_id,source_type,selection_tier,publisher,title,url,published_at,is_primary,
                     content_hash,content_storage_uri,retrieved_at,created_at,updated_at)
                    VALUES (UUID_TO_BIN(?),NULL,?,?,?,?,?,?,b'0',?,NULL,?,?,?)
                    """)) {
                int i = 1;
                p.setString(i++, d.id().toString());
                p.setString(i++, d.sourceType()); p.setString(i++, d.selectionTier());
                p.setString(i++, d.publisher()); p.setString(i++, d.title()); p.setString(i++, d.url());
                p.setTimestamp(i++, Timestamp.from(d.published()), UTC);
                p.setString(i++, d.contentHash());
                p.setTimestamp(i++, Timestamp.from(importedAt), UTC);
                p.setTimestamp(i++, Timestamp.from(importedAt), UTC);
                p.setTimestamp(i, Timestamp.from(importedAt), UTC);
                p.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("""
                    INSERT INTO source_document_chunks (id,source_document_id,content,chunk_order,created_at)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?)
                    """)) {
                for (Chunk c : d.chunks()) {
                    p.setString(1, c.id().toString()); p.setString(2, d.id().toString());
                    p.setString(3, c.text()); p.setInt(4, c.order());
                    p.setTimestamp(5, Timestamp.from(importedAt), UTC);
                    p.addBatch();
                }
                p.executeBatch();
            }
            try (PreparedStatement p = db.prepareStatement("""
                    INSERT INTO source_document_targets (source_document_id,market_target_id)
                    VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?))
                    """)) {
                for (UUID target : d.targets()) {
                    p.setString(1, d.id().toString()); p.setString(2, target.toString()); p.addBatch();
                }
                p.executeBatch();
            }
        }
    }

    private static Map<String, Object> inventoryFromDb(Connection db, PackageData pkg) throws SQLException {
        // Re-read every stored source chunk and reconstruct the hash from actual bytes, not incoming JSON.
        // Also re-check market links and all immutable document fields before reporting success.
        checkDatabase(db, pkg);
        List<Object> documents = new ArrayList<>();
        for (Document d : pkg.documents()) {
            Map<UUID, Chunk> actual = readChunks(db, d.id());
            StringBuilder storedBody = new StringBuilder();
            List<Object> chunks = new ArrayList<>();
            for (Chunk expected : d.chunks()) {
                Chunk c = actual.get(expected.id());
                if (c == null || c.order() != expected.order()) throw conflict("stored chunk missing or out of order");
                storedBody.append(c.text());
                chunks.add(Map.of("chunkId", c.id().toString(), "textHash", sha256(c.text())));
            }
            if (!sha256(storedBody.toString()).equals(d.contentHash())) throw conflict("stored document SHA-256 mismatch: " + d.id());
            documents.add(Map.of("sourceDocumentId", d.id().toString(), "contentHash", sha256(storedBody.toString()), "chunks", chunks));
        }
        return Map.of("packageId", pkg.id(), "documents", documents);
    }

    private static boolean documentExists(Connection db, UUID id) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT 1 FROM source_documents WHERE id=UUID_TO_BIN(?)")) {
            p.setString(1, id.toString()); try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }

    private static Map<UUID, Chunk> readChunks(Connection db, UUID documentId) throws SQLException {
        Map<UUID, Chunk> result = new HashMap<>();
        try (PreparedStatement p = db.prepareStatement("""
                SELECT BIN_TO_UUID(id),content,chunk_order FROM source_document_chunks
                WHERE source_document_id=UUID_TO_BIN(?) ORDER BY chunk_order
                """)) {
            p.setString(1, documentId.toString());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    UUID id = UUID.fromString(r.getString(1));
                    String body = r.getString(2);
                    result.put(id, new Chunk(id, body, r.getInt(3), sha256(body)));
                }
            }
        }
        return result;
    }

    private static Set<UUID> readTargets(Connection db, UUID documentId) throws SQLException {
        Set<UUID> result = new HashSet<>();
        try (PreparedStatement p = db.prepareStatement("""
                SELECT BIN_TO_UUID(market_target_id) FROM source_document_targets WHERE source_document_id=UUID_TO_BIN(?)
                """)) {
            p.setString(1, documentId.toString());
            try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(UUID.fromString(r.getString(1))); }
        }
        return result;
    }

    private static String aiRequest(String method, String path, String body) throws Exception {
        String base = getenvRequired("AI_RAG_BASE_URL");
        String token = getenvRequired("AI_RAG_BEARER_TOKEN");
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) req.GET();
        else req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw conflict("AI registration HTTP " + response.statusCode() + "; no success receipt");
        return response.body();
    }

    /** Python json.dumps(sort_keys=True, ensure_ascii=False, separators=(',',':')) for v1 JSON types. */
    private static String canonical(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object key : m.keySet()) keys.add(stringExact(key, "JSON key"));
            Collections.sort(keys);
            List<String> items = new ArrayList<>();
            for (String key : keys) items.add(JSON.writeValueAsString(key) + ":" + canonical(m.get(key)));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) values.add(canonical(item));
            return "[" + String.join(",", values) + "]";
        }
        if (value instanceof String s) return JSON.writeValueAsString(s);
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        throw conflict("Unsupported v1 JSON type in canonical hash");
    }

    private static String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o, String field) {
        if (!(o instanceof Map<?, ?> raw)) throw conflict("Expected JSON object: " + field);
        for (Object k : raw.keySet()) if (!(k instanceof String)) throw conflict("Non-string key: " + field);
        return (Map<String, Object>) raw;
    }
    private static List<?> array(Object o, String field) {
        if (!(o instanceof List<?> list)) throw conflict("Expected JSON array: " + field);
        return list;
    }
    private static String stringExact(Object o, String field) {
        if (!(o instanceof String s)) throw conflict("Expected string: " + field);
        return s;
    }
    private static String text(Object o, String field) {
        String s = stringExact(o, field);
        if (s.isBlank()) throw conflict("Empty string: " + field);
        return s;
    }
    private static String limited(Object o, String field, int max) {
        String s = text(o, field);
        if (s.length() > max) throw conflict("Column overflow: " + field);
        return s;
    }
    private static int integer(Object o, String field) {
        if (!(o instanceof Integer i)) throw conflict("Expected integer: " + field);
        return i;
    }
    private static UUID uuid(Object o, String field) {
        try { return UUID.fromString(text(o, field)); }
        catch (IllegalArgumentException ex) { throw conflict("Invalid UUID: " + field); }
    }
    private static String getenvRequired(String name) {
        String result = System.getenv(name);
        if (result == null || result.isBlank()) throw conflict("Missing environment variable " + name);
        return result;
    }
    private static IllegalStateException conflict(String msg) { return new IllegalStateException(msg); }
}
