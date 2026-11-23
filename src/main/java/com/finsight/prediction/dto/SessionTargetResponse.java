package com.finsight.prediction.dto;

import com.finsight.prediction.entity.SessionTarget;

import java.util.List;
import java.util.UUID;

public record SessionTargetResponse(
        UUID learningSessionId,
        List<SessionTargetItemResponse> targets
) {
    public static SessionTargetResponse from(
            UUID learningSessionId,
            List<SessionTarget> sessionTargets
    ) {
        return new SessionTargetResponse(
                learningSessionId,
                sessionTargets.stream()
                        .map(SessionTargetItemResponse::from)
                        .toList()
        );
    }
}
