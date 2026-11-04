package com.finsight.feedback.service;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.feedback.repository.FeedbackSourceDocumentChunkRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verify actual stored source bytes, not only referential existence. */
@Component
@RequiredArgsConstructor
public class ApprovedSourceIntegrityVerifier {
    private final FeedbackSourceDocumentRepository documentRepository;
    private final FeedbackSourceDocumentChunkRepository chunkRepository;

    public void verify(List<ApprovedCitationWriter.Citation> citations) {
        Set<UUID> ids = new HashSet<>();
        for (ApprovedCitationWriter.Citation citation : citations) {
            ids.add(citation.sourceDocumentId());
        }
        if (ids.isEmpty()) return;
        Map<UUID, String> expectedHashes = new HashMap<>();
        for (FeedbackSourceDocument doc : documentRepository.findAllById(ids)) {
            expectedHashes.put(doc.getId(), doc.getContentHash());
        }
        if (!expectedHashes.keySet().containsAll(ids)) {
            throw new IllegalStateException("approved_source_document_missing");
        }
        Map<UUID, StringBuilder> contents = new HashMap<>();
        Map<UUID, Set<UUID>> chunkIdsByDocument = new HashMap<>();
        Map<UUID, Integer> lastOrder = new HashMap<>();
        for (FeedbackSourceDocumentChunk chunk : chunkRepository.findAllForDocuments(ids)) {
            UUID docId = chunk.getSourceDocumentId();
            int expectedOrder = lastOrder.getOrDefault(docId, -1) + 1;
            if (chunk.getChunkOrder() == null || chunk.getChunkOrder() != expectedOrder
                    || chunk.getContent() == null) {
                throw new IllegalStateException("approved_source_chunk_order_or_content_invalid");
            }
            lastOrder.put(docId, expectedOrder);
            contents.computeIfAbsent(docId, ignored -> new StringBuilder()).append(chunk.getContent());
            chunkIdsByDocument.computeIfAbsent(docId, ignored -> new HashSet<>()).add(chunk.getId());
        }
        for (UUID id : ids) {
            StringBuilder body = contents.get(id);
            if (body == null || !sha256(body.toString()).equals(expectedHashes.get(id))) {
                throw new IllegalStateException("approved_source_document_hash_mismatch");
            }
        }
        for (ApprovedCitationWriter.Citation citation : citations) {
            if (!chunkIdsByDocument.getOrDefault(citation.sourceDocumentId(), Set.of())
                    .contains(citation.chunkId())) {
                throw new IllegalStateException("approved_source_chunk_missing_or_mislinked");
            }
        }
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("approved_sha256_unavailable", e);
        }
    }
}
