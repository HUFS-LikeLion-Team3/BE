package com.finsight.outcome.dto;

import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.prediction.entity.SessionTarget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OutcomeResponse(
        UUID learningSessionId,
        List<TargetResponse> targets
) {

    public record TargetResponse(
            UUID sessionTargetId,
            UUID marketTargetId,
            BaselineResponse baseline,
            List<ObservationResponse> observations
    ) {

        public static TargetResponse from(
                SessionTarget sessionTarget,
                SessionTargetBaseline baseline,
                List<MarketObservation> observations
        ) {
            return new TargetResponse(
                    sessionTarget.getId(),
                    sessionTarget.getMarketTargetId(),
                    BaselineResponse.from(baseline),
                    observations.stream()
                            .map(ObservationResponse::from)
                            .toList()
            );
        }
    }

    public record BaselineResponse(
            String status,
            LocalDate baselineSessionDate,
            OffsetDateTime baselineAt,
            BigDecimal baselineValue,
            String unit,
            String dataSourceName,
            String dataSourceUrl,
            String unavailableReason,
            LocalDate expectedBaselineDate,
            LocalDate baselineDeadlineDate
    ) {

        public static BaselineResponse from(
                SessionTargetBaseline baseline
        ) {
            return new BaselineResponse(
                    baseline.getStatus().name(),
                    baseline.getBaselineSessionDate(),
                    baseline.getBaselineAt(),
                    baseline.getBaselineValue(),
                    baseline.getUnit(),
                    baseline.getDataSourceName(),
                    baseline.getDataSourceUrl(),
                    baseline.getUnavailableReason(),
                    baseline.getExpectedBaselineDate(),
                    baseline.getBaselineDeadlineDate()
            );
        }
    }

    public record ObservationResponse(
            Integer horizon,
            String status,
            String exceptionStatus,
            OffsetDateTime referenceAt,
            LocalDate expectedObservationDate,
            LocalDate observationDeadlineDate,
            LocalDate observationSessionDate,
            OffsetDateTime observedAt,
            BigDecimal observedValue,
            Integer observationDelaySessions,
            BigDecimal changeValue,
            BigDecimal changePercent,
            boolean usableForAiEvidence,
            String unit,
            String dataSourceName,
            String dataSourceUrl,
            String unavailableReason
    ) {

        public static ObservationResponse from(
                MarketObservation observation
        ) {
            return new ObservationResponse(
                    observation.getHorizon(),
                    observation.getStatus().name(),
                    observation.getExceptionStatus().name(),
                    observation.getReferenceAt(),
                    observation.getExpectedObservationDate(),
                    observation.getObservationDeadlineDate(),
                    observation.getObservationSessionDate(),
                    observation.getObservedAt(),
                    observation.getObservedValue(),
                    observation.getObservationDelaySessions(),
                    observation.getChangeValue(),
                    observation.getChangePercent(),
                    observation.isUsableForAiEvidence(),
                    observation.getUnit(),
                    observation.getDataSourceName(),
                    observation.getDataSourceUrl(),
                    observation.getUnavailableReason()
            );
        }
    }
}