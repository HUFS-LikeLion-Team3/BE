package com.finsight.pattern.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 시장 범주 × 기간별 관측 결과 사례 비교. 적중 여부·점수는 계산하지 않는다.
 * stage: insufficient(서로 다른 뉴스 3개 미만), initial_observation(3~4개), repeated_tendency(5개 이상).
 */
public record OutcomePatternResponse(
        String market,
        int horizon,
        long distinctNewsCount,
        int minimumNewsCount,
        int repeatedNewsCount,
        String stage,
        List<OutcomeCase> cases
) {
    public record OutcomeCase(
            UUID sessionId,
            UUID newsId,
            String newsTitle,
            String targetCode,
            String targetName,
            String unit,
            String direction,
            String reason,
            LocalDate baselineDate,
            BigDecimal baselineValue,
            LocalDate observationDate,
            BigDecimal observedValue,
            BigDecimal changeValue,
            BigDecimal changePercent,
            String reflection
    ) {
    }
}
