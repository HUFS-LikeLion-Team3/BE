package com.finsight.pattern.dto;

import com.finsight.learning.entity.LearningSession;
import com.finsight.prediction.entity.Prediction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 확정(ready) 관측값이 있는 제출 예측 한 건의 읽기 전용 투영. */
public record OutcomePatternRow(
        UUID sessionId,
        UUID sessionTargetId,
        UUID newsId,
        String newsTitle,
        String targetCode,
        String targetName,
        String unit,
        Integer sortOrder,
        Short horizon,
        Prediction.Direction direction,
        String reason,
        LocalDate baselineDate,
        BigDecimal baselineValue,
        LocalDate observationDate,
        BigDecimal observedValue,
        BigDecimal changeValue,
        BigDecimal changePercent,
        OffsetDateTime submittedAt,
        LearningSession.SessionType sessionType,
        OffsetDateTime resultsRevealedAt
) {
}
