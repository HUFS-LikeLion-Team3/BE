package com.finsight.outcome.entity;

import jakarta.persistence.*;
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

    public enum OutcomeCollectionEventType {
        market_holiday,
        trading_halt,
        trading_resumed,
        data_missing,
        unavailable
    }
}