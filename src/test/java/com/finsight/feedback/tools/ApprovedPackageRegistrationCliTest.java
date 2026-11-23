package com.finsight.feedback.tools;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedPackageRegistrationCliTest {

    private String fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/issue26/demo-package.json")) {
            if (stream == null) throw new IllegalStateException("Missing demo fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String issue36Fixture(String filename) throws Exception {
        try (var stream = getClass().getResourceAsStream("/issue36/" + filename)) {
            if (stream == null) throw new IllegalStateException("Missing issue36 fixture: " + filename);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void databaseSelectionDefaultsToProductionAndAllowsOnlyExplicitIssue36Clone() {
        assertEquals("finsight", ApprovedPackageRegistrationCli.allowedDatabaseSchema(null));
        assertEquals("finsight", ApprovedPackageRegistrationCli.allowedDatabaseSchema(""));
        assertEquals("finsight", ApprovedPackageRegistrationCli.allowedDatabaseSchema("finsight"));
        assertEquals("finsight_issue36", ApprovedPackageRegistrationCli.allowedDatabaseSchema("finsight_issue36"));
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.allowedDatabaseSchema("finsight_prod"));
    }

    @Test
    void databaseUrlMustSelectTheExactExpectedSchema() {
        ApprovedPackageRegistrationCli.assertDatabaseUrl(
                "jdbc:mysql://localhost:3306/finsight_issue36?serverTimezone=Asia/Seoul", "finsight_issue36");
        ApprovedPackageRegistrationCli.assertDatabaseUrl(
                "jdbc:mysql://localhost:3306/finsight?serverTimezone=Asia/Seoul", "finsight");
        assertThrows(IllegalStateException.class, () -> ApprovedPackageRegistrationCli.assertDatabaseUrl(
                "jdbc:mysql://localhost:3306/finsight", "finsight_issue36"));
        assertThrows(IllegalStateException.class, () -> ApprovedPackageRegistrationCli.assertDatabaseUrl(
                "jdbc:mysql://localhost:3306/finsight_issue36_copy", "finsight_issue36"));
        assertThrows(IllegalStateException.class, () -> ApprovedPackageRegistrationCli.assertDatabaseUrl(
                "jdbc:postgresql://localhost/finsight_issue36", "finsight_issue36"));
    }

    @Test
    void keepsPrimaryOfficialTierZeroMapping() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier0-official-package.json"));
        assertEquals("primary_official", pkg.documents().get(0).selectionTier());
        assertEquals("government_release", pkg.documents().get(0).sourceType());
    }

    @Test
    void mapsTierTwoResearchReportToTrustedResearchWithoutRewritingPackage() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier2-research-package.json"));
        var doc = pkg.documents().get(0);
        assertEquals("trusted_research", doc.selectionTier());
        assertEquals("research_report", doc.sourceType());
        assertEquals("dd78bd77074d70fe364f9c337b3dcf9b0c3012d0bef9523a57b16db0b58d1f04", doc.contentHash());
        assertEquals("sha256:d24597edd825006029aec9d1684cba7c3bb1d09588663b15146771a8f0de2b75", pkg.id());
        assertEquals(pkg.id(), ApprovedPackageRegistrationCli.inventory(pkg).get("packageId"));
        assertEquals(1, doc.chunks().size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> packageBody() throws Exception {
        return JsonMapper.builder().build().readValue(fixture(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentEntry(Map<String, Object> body) {
        return (Map<String, Object>) ((List<?>) body.get("documents")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document(Map<String, Object> body) {
        return (Map<String, Object>) documentEntry(body).get("document");
    }

    private Object sortedJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, child) -> sorted.put((String) key, sortedJson(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sortedJson).toList();
        return value;
    }

    // Re-seal the outer package so negative tests reach the inner validation.
    private String reseal(Map<String, Object> body) throws Exception {
        var json = JsonMapper.builder().build();
        body.remove("packageId");
        byte[] canonical = json.writeValueAsString(sortedJson(body)).getBytes(StandardCharsets.UTF_8);
        body.put("packageId", "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical)));
        return json.writeValueAsString(body);
    }

    @Test
    void preservesSupportedSourceTypesAndTiersIndependently() throws Exception {
        var names = List.of("primary_official", "original_article", "trusted_research");
        var original = ApprovedPackageRegistrationCli.validate(fixture()).documents().get(0);
        for (int tier = 0; tier < names.size(); tier++) {
            for (String sourceType : List.of("original_article", "official_statistic",
                    "government_release", "central_bank_release", "research_report")) {
                var body = packageBody();
                document(body).put("selection_tier", tier);
                documentEntry(body).put("sourceType", sourceType);
                var pkg = ApprovedPackageRegistrationCli.validate(reseal(body));
                var doc = pkg.documents().get(0);
                assertEquals(names.get(tier), doc.selectionTier());
                assertEquals(sourceType, doc.sourceType());
                assertEquals(original.id(), doc.id());
                assertEquals(original.contentHash(), doc.contentHash());
                assertEquals(original.chunks(), doc.chunks());
                assertEquals(original.targets(), doc.targets());
                assertEquals(pkg.id(), ApprovedPackageRegistrationCli.inventory(pkg).get("packageId"));
            }
        }
    }

    @Test
    void acceptsFormerlyRejectedMetadataFixturesWithoutRewritingThem() throws Exception {
        var research = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier1-research-package.json"));
        assertEquals("original_article", research.documents().get(0).selectionTier());
        assertEquals("research_report", research.documents().get(0).sourceType());
        var article = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier2-original-article-package.json"));
        assertEquals("trusted_research", article.documents().get(0).selectionTier());
        assertEquals("original_article", article.documents().get(0).sourceType());
    }

    @Test
    void rejectsUnknownMetadataValuesWithValidPackageHash() throws Exception {
        var unknownTier = assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(issue36Fixture("tier3-unsupported-package.json")));
        assertEquals("unconfirmed selection_tier mapping: 3", unknownTier.getMessage());
        for (int tier : List.of(-1, 99)) {
            var body = packageBody();
            document(body).put("selection_tier", tier);
            String raw = reseal(body);
            assertEquals("unconfirmed selection_tier mapping: " + tier,
                    assertThrows(IllegalStateException.class,
                            () -> ApprovedPackageRegistrationCli.validate(raw)).getMessage());
        }
        var body = packageBody();
        documentEntry(body).put("sourceType", "unknown_source");
        String raw = reseal(body);
        assertEquals("unsupported sourceType unknown_source",
                assertThrows(IllegalStateException.class,
                        () -> ApprovedPackageRegistrationCli.validate(raw)).getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void independentMetadataDoesNotBypassInnerContentOrChunkIntegrity() throws Exception {
        var body = packageBody();
        document(body).put("selection_tier", 2);
        documentEntry(body).put("sourceType", "central_bank_release");
        document(body).put("content_hash", "0".repeat(64));
        String badContent = reseal(body);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(badContent))
                .getMessage().startsWith("document SHA-256 mismatch:"));

        body = packageBody();
        document(body).put("selection_tier", 2);
        documentEntry(body).put("sourceType", "central_bank_release");
        var chunk = (Map<String, Object>) ((List<?>) document(body).get("stored_chunks")).get(0);
        chunk.put("text", "tampered chunk");
        String badChunk = reseal(body);
        assertTrue(assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(badChunk))
                .getMessage().startsWith("chunk text differs from approved document:"));
    }

    @Test
    void validatesExactUtf8PackageAndBuildsInventory() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(fixture());
        assertEquals("sha256:3f8f06b3d8c41b2b0035ecdcdb7c3a2de529431615984ac68d3091351f23226e", pkg.id());
        assertEquals(1, pkg.documents().size());
        assertEquals("original_article", pkg.documents().get(0).selectionTier());
        assertEquals(1, pkg.documents().get(0).chunks().size());
        assertEquals(UUID.fromString("c5d99dc5-0eff-5509-a1a9-1aa4be9750db"), pkg.marketIds().iterator().next());
        Map<String, Object> inv = ApprovedPackageRegistrationCli.inventory(pkg);
        assertEquals(pkg.id(), inv.get("packageId"));
        assertEquals(1, ((java.util.List<?>) inv.get("documents")).size());
    }

    @Test
    void alteredBodyOrChunkFailsBeforeDbOrAiRequest() throws Exception {
        String input = fixture();
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("FinSight local integration fixture.", "tampered body!")));
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("승인·인용 저장 연결 검증용 대역 본문입니다.", "변조된 텍스트")));
    }

    @Test
    void noSilentRebindingOrDataTypeGuess() throws Exception {
        String input = fixture();
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("\"selection_tier\": 1", "\"selection_tier\": 99")));
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("\"linked_news_ids\": []", "\"linked_news_ids\": [\"11111111-1111-4111-8111-111111111111\"]")));
        assertTrue(ApprovedPackageRegistrationCli.validate(input).documents().get(0).chunks().get(0).text().contains("승인·인용"));
    }
}
