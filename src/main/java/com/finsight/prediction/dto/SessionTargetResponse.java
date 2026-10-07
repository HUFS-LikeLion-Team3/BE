package com.finsight.prediction.dto;

import com.finsight.prediction.entity.SessionTarget;

import java.util.List;
import java.util.UUID;

public record SessionTargetResponse(
        UUID sessionId,
        List<SessionTargetItemResponse> targets
) {

    public static SessionTargetResponse from(
            UUID sessionId,
            List<SessionTarget> sessionTargets
    ) {
        return new SessionTargetResponse(
                sessionId,
                sessionTargets.stream()
                        .map(SessionTargetItemResponse::from)
                        .toList()
        );
    }
}
