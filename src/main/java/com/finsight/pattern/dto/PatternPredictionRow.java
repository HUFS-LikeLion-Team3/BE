package com.finsight.pattern.dto;

import com.finsight.prediction.entity.Prediction;
import java.util.UUID;

/** 제출된 예측 행의 읽기 전용 투영. newsId는 서로 다른 뉴스 단위 집계에 사용한다. */
public record PatternPredictionRow(
        UUID sessionId,
        UUID newsId,
        String newsCategory,
        Short horizon,
        Prediction.Direction direction,
        String reason
) {
}
