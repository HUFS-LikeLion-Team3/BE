package com.finsight.learning.dto;

import com.finsight.learning.entity.LearningSession;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LearningSessionDetailResponse(
        UUID id,
        UUID newsId,
        String sessionType,
        String status,
        OffsetDateTime resultsRevealedAt,
        OffsetDateTime savedAt,
        OffsetDateTime submittedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LearningSessionDetailResponse from(
            LearningSession session
    ) {
        return new LearningSessionDetailResponse(
                session.getId(),
                session.getNewsId(),
                session.getSessionType().name(),
                session.getStatus().name(),
                session.getResultsRevealedAt(),
                session.getSavedAt(),
                session.getSubmittedAt(),
                session.getCompletedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
