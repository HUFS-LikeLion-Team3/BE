package com.finsight.prediction.dto;

import com.finsight.prediction.entity.Prediction;

public record PredictionItemResponse(int horizon, String direction, String reason) {
    public static PredictionItemResponse from(Prediction prediction) {
        return new PredictionItemResponse(
                prediction.getHorizon(),
                prediction.getDirection() == null ? null : prediction.getDirection().name(),
                prediction.getReason()
        );
    }

    public static PredictionItemResponse empty(int horizon) {
        return new PredictionItemResponse(horizon, null, null);
    }
}
