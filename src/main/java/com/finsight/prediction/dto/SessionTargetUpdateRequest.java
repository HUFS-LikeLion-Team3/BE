package com.finsight.prediction.dto;

import java.util.List;
import java.util.UUID;

public record SessionTargetUpdateRequest(
        List<TargetRequest> targets
) {

    public record TargetRequest(
            UUID marketTargetId,
            Integer sortOrder
    ) {
    }
}
