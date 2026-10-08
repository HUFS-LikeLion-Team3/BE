package com.finsight.outcome.dto;

import com.finsight.outcome.entity.OutcomeCollectionEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutcomeEventResponse(
        UUID id,
        UUID sessionTargetId,
        Integer horizon,
        String eventType,
        OffsetDateTime occurredAt,
        String detail,
        String dataSourceName,
        String dataSourceUrl,
        OffsetDateTime createdAt
) {

    public static OutcomeEventResponse from(
            OutcomeCollectionEvent event
    ) {
        return new OutcomeEventResponse(
                event.getId(),
                event.getSessionTargetId(),
                event.getHorizon(),
                event.getEventType().name(),
                event.getOccurredAt(),
                event.getDetail(),
                event.getDataSourceName(),
                event.getDataSourceUrl(),
                event.getCreatedAt()
        );
    }
}