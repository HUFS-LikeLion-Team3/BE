package com.finsight.pattern.dto;

import com.finsight.prediction.entity.Prediction;
import java.util.UUID;

/** 제출된 예측 행의 읽기 전용 투영. newsCategory는 News.category에서 조회한다. */
public record PatternPredictionRow(
        UUID sessionId,
        String newsCategory,
        Short horizon,
        Prediction.Direction direction,
        String reason
) {
}
