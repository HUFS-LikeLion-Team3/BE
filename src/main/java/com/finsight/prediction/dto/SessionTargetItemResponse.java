package com.finsight.prediction.dto;

import com.finsight.prediction.entity.SessionTarget;

import java.util.UUID;

public record SessionTargetItemResponse(
        UUID sessionTargetId,
        UUID marketTargetId,
        boolean isSelected,
        Integer sortOrder
) {

    public static SessionTargetItemResponse from(
            SessionTarget sessionTarget
    ) {
        return new SessionTargetItemResponse(
                sessionTarget.getId(),
                sessionTarget.getMarketTargetId(),
                sessionTarget.isSelected(),
                sessionTarget.getSortOrder()
        );
    }
}
