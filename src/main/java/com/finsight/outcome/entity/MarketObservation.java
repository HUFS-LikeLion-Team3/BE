package com.finsight.outcome.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "market_observations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_market_observations_target_horizon",
                        columnNames = {"session_target_id", "horizon"}
                )
        }
)
@Getter
@NoArgsConstructor
public class MarketObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Column(name = "baseline_id", nullable = false)
    private UUID baselineId;

    @Column(nullable = false)
    private Integer horizon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutcomeStatus status = OutcomeStatus.pending;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_status", nullable = false)
    private OutcomeExceptionStatus exceptionStatus =
            OutcomeExceptionStatus.none;

    @Column(name = "reference_at", nullable = false)
    private OffsetDateTime referenceAt;

    @Column(name = "expected_observation_date")
    private LocalDate expectedObservationDate;

    @Column(name = "observation_deadline_date")
    private LocalDate observationDeadlineDate;

    @Column(name = "observation_session_date")
    private LocalDate observationSessionDate;

    @Column(name = "observed_at")
    private OffsetDateTime observedAt;

    @Column(name = "observed_value", precision = 20, scale = 6)
    private BigDecimal observedValue;

    @Column(
            name = "observation_delay_sessions",
            nullable = false
    )
    private Integer observationDelaySessions = 0;

    @Column(name = "change_value", precision = 20, scale = 6)
    private BigDecimal changeValue;

    @Column(name = "change_percent", precision = 12, scale = 6)
    private BigDecimal changePercent;

    @Column(name = "usable_for_ai_evidence", nullable = false)
    private boolean usableForAiEvidence = false;

    @Column(nullable = false)
    private String unit;

    @Column(name = "data_source_name")
    private String dataSourceName;

    @Column(name = "data_source_url")
    private String dataSourceUrl;

    @Column(name = "unavailable_reason", columnDefinition = "TEXT")
    private String unavailableReason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public enum OutcomeStatus {
        pending,
        ready,
        unavailable
    }

    public enum OutcomeExceptionStatus {
        none,
        market_holiday,
        trading_halt,
        data_missing,
        delisted,
        long_term_halt
    }
}