package com.finsight.feedback.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record FeedbackGenerationInput(
        OffsetDateTime inputCutoffAt,
        Map<String, Object> requestSnapshot,
        Map<String, Object> predictionSnapshot,
        Map<String, Object> marketBaselineSnapshot,
        Map<String, Object> marketResultSnapshot,
        Map<String, Object> reflectionSnapshot,
        String inputHash,
        Set<UUID> allowedTargetIds,
        Map<UUID, EvidenceChunk> evidenceChunks
) {

    public record EvidenceChunk(
            UUID chunkId,
            UUID sourceDocumentId,
            String title,
            String publisher,
            String url,
            String publishedAt,
            String contentHash,
            String content
    ) {
    }
}