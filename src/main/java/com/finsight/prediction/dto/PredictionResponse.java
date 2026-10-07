package com.finsight.prediction.dto;

import java.util.List;
import java.util.UUID;

public record PredictionResponse(
        UUID sessionTargetId,
        List<PredictionItemResponse> predictions
) {
}
