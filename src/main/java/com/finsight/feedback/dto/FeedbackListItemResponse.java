package com.finsight.feedback.dto;

import com.finsight.feedback.entity.AiFeedback;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FeedbackListItemResponse(
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
        OffsetDateTime updatedAt
) {

    public static FeedbackListItemResponse from(AiFeedback feedback) {
        return new FeedbackListItemResponse(
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
                feedback.getFlowSummary(),
                feedback.getPromptVersion(),
                feedback.getPolicyVersion(),
                feedback.getInputCutoffAt(),
                feedback.getInsufficientEvidence(),
                feedback.getGeneratedAt(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt()
        );
    }
}