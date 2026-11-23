package com.finsight.outcome.dto;

import com.finsight.outcome.entity.OutcomeSeriesPoint;
import com.finsight.prediction.entity.SessionTarget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OutcomeSeriesResponse(
        UUID learningSessionId,
        UUID sessionTargetId,
        UUID marketTargetId,
        List<SeriesPointResponse> series
) {

    public static OutcomeSeriesResponse from(
            UUID learningSessionId,
            SessionTarget sessionTarget,
            List<OutcomeSeriesPoint> seriesPoints
    ) {
        return new OutcomeSeriesResponse(
                learningSessionId,
                sessionTarget.getId(),
                sessionTarget.getMarketTargetId(),
                seriesPoints.stream()
                        .map(SeriesPointResponse::from)
                        .toList()
        );
    }

    public record SeriesPointResponse(
            LocalDate tradingDate,
            BigDecimal closeValue,
            String unit,
            String dataSourceName,
            String dataSourceUrl
    ) {

        public static SeriesPointResponse from(
                OutcomeSeriesPoint seriesPoint
        ) {
            return new SeriesPointResponse(
                    seriesPoint.getTradingDate(),
                    seriesPoint.getCloseValue(),
                    seriesPoint.getUnit(),
                    seriesPoint.getDataSourceName(),
                    seriesPoint.getDataSourceUrl()
            );
        }
    }
}