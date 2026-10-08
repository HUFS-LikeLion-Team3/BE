package com.finsight.feedback.dto;

import com.finsight.feedback.entity.AiFeedback;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FeedbackDetailResponse(
        UUID id,
        UUID learningSessionId,
        String feedbackStage,
        String status,
        Integer version,
        Boolean isLatest,
        String requestId,
        Integer retryCount,
        String failureCode,
        String modelVersion,
        String flowSummary,
        String promptVersion,
        String policyVersion,
        OffsetDateTime inputCutoffAt,
        Boolean insufficientEvidence,
        OffsetDateTime generatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<TargetFeedbackResponse> targetFeedbacks,
        List<ReferenceResponse> references
) {

    public static FeedbackDetailResponse from(
            AiFeedback feedback,
            String flowSummary,
            List<TargetFeedbackResponse> targetFeedbacks,
            List<ReferenceResponse> references
    ) {
        return new FeedbackDetailResponse(
                feedback.getId(),
                feedback.getLearningSessionId(),
                feedback.getFeedbackStage().getValue(),
                feedback.getStatus().name(),
                feedback.getVersion(),
                feedback.getIsLatest(),
                feedback.getRequestId(),
                feedback.getRetryCount(),
                feedback.getFailureCode(),
                feedback.getModelVersion(),
                flowSummary,
                feedback.getPromptVersion(),
                feedback.getPolicyVersion(),
                feedback.getInputCutoffAt(),
                feedback.getInsufficientEvidence(),
                feedback.getGeneratedAt(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt(),
                targetFeedbacks,
                references
        );
    }

    public record TargetFeedbackResponse(
            UUID sessionTargetId,
            List<FeedbackItemResponse> items
    ) {
    }

    public record FeedbackItemResponse(
            UUID id,
            String type,
            String content,
            List<UUID> referenceIds,
            Integer sortOrder
    ) {
    }

    public record ReferenceResponse(
            UUID id,
            UUID sourceDocumentId,
            UUID chunkId,
            String title,
            String publisher,
            OffsetDateTime publishedAt,
            String url,
            String contentHash,
            String citationLabel,
            String citationExcerpt,
            String usageContext
    ) {
    }
}