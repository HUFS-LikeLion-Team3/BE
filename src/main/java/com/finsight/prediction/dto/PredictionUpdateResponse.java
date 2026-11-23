package com.finsight.prediction.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PredictionUpdateResponse(
        UUID sessionTargetId,
        List<PredictionItemResponse> predictions,
        OffsetDateTime updatedAt
) {
}
