package com.finsight.learning.dto;

import com.finsight.learning.entity.LearningSession;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LearningSessionListItemResponse(
        UUID id,
        UUID newsId,
        String sessionType,
        String status,
        OffsetDateTime submittedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LearningSessionListItemResponse from(LearningSession session) {
        return new LearningSessionListItemResponse(
                session.getId(),
                session.getNewsId(),
                session.getSessionType().name(),
                session.getStatus().name(),
                session.getSubmittedAt(),
                session.getCompletedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
