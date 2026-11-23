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
                LearningSessionDateTime.toKorea(session.getResultsRevealedAt()),
                LearningSessionDateTime.toKorea(session.getSavedAt()),
                LearningSessionDateTime.toKorea(session.getSubmittedAt()),
                LearningSessionDateTime.toKorea(session.getCompletedAt()),
                LearningSessionDateTime.toKorea(session.getCreatedAt()),
                LearningSessionDateTime.toKorea(session.getUpdatedAt())
        );
    }
}
