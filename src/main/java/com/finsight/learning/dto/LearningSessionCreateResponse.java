package com.finsight.learning.dto;

import com.finsight.learning.entity.LearningSession;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LearningSessionCreateResponse(
        UUID id,
        UUID newsId,
        String sessionType,
        String status,
        OffsetDateTime createdAt
) {

    public static LearningSessionCreateResponse from(LearningSession session) {
        return new LearningSessionCreateResponse(
                session.getId(),
                session.getNewsId(),
                session.getSessionType().name(),
                session.getStatus().name(),
                session.getCreatedAt()
        );
    }
}
