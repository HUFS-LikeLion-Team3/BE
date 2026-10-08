package com.finsight.outcome.dto;

import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.prediction.entity.SessionTarget;

import java.util.List;
import java.util.UUID;

public record TargetOutcomeResponse(
        UUID learningSessionId,
        UUID sessionTargetId,
        UUID marketTargetId,
        OutcomeResponse.BaselineResponse baseline,
        List<OutcomeResponse.ObservationResponse> observations
) {

    public static TargetOutcomeResponse from(
            UUID learningSessionId,
            SessionTarget sessionTarget,
            SessionTargetBaseline baseline,
            List<MarketObservation> observations
    ) {
        return new TargetOutcomeResponse(
                learningSessionId,
                sessionTarget.getId(),
                sessionTarget.getMarketTargetId(),
                OutcomeResponse.BaselineResponse.from(baseline),
                observations.stream()
                        .map(OutcomeResponse.ObservationResponse::from)
                        .toList()
        );
    }
}
