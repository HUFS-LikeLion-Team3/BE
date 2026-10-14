package com.finsight.outcome.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outcome_collection_events")
@Getter
@NoArgsConstructor
public class OutcomeCollectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Column
    private Integer horizon;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private OutcomeCollectionEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "data_source_name")
    private String dataSourceName;

    @Column(name = "data_source_url")
    private String dataSourceUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public static OutcomeCollectionEvent create(
            UUID sessionTargetId,
            Integer horizon,
            OutcomeCollectionEventType eventType,
            OffsetDateTime occurredAt,
            String detail,
            String dataSourceName,
            String dataSourceUrl
    ) {
        if (sessionTargetId == null
                || eventType == null
                || occurredAt == null) {
            throw new IllegalArgumentException(
                    "Invalid outcome collection event"
            );
        }

        if (horizon != null
                && horizon != 1
                && horizon != 5
                && horizon != 20) {
            throw new IllegalArgumentException(
                    "Invalid event horizon"
            );
        }

        OutcomeCollectionEvent event =
                new OutcomeCollectionEvent();

        event.sessionTargetId = sessionTargetId;
        event.horizon = horizon;
        event.eventType = eventType;
        event.occurredAt = occurredAt;
        event.detail = detail;
        event.dataSourceName = dataSourceName;
        event.dataSourceUrl = dataSourceUrl;

        return event;
    }

    public enum OutcomeCollectionEventType {
        market_holiday,
        trading_halt,
        trading_resumed,
        data_missing,
        unavailable
    }
}