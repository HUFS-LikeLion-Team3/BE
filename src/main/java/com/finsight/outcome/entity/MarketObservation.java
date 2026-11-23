package com.finsight.outcome.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    // T1/T5/T20 최초 관측 행 생성
    public static MarketObservation createPending(
            UUID sessionTargetId,
            UUID baselineId,
            int horizon,
            OffsetDateTime referenceAt,
            LocalDate expectedDate,
            LocalDate deadlineDate,
            String unit
    ) {
        if (sessionTargetId == null || baselineId == null) {
            throw new IllegalArgumentException(
                    "Target and baseline IDs required"
            );
        }

        if (horizon != 1 && horizon != 5 && horizon != 20) {
            throw new IllegalArgumentException(
                    "Invalid observation horizon"
            );
        }

        if (referenceAt == null
                || unit == null
                || unit.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid observation initialization"
            );
        }

        if ((expectedDate == null) != (deadlineDate == null)) {
            throw new IllegalArgumentException(
                    "Observation dates must both be null or non-null"
            );
        }

        if (expectedDate != null
                && deadlineDate.isBefore(expectedDate)) {
            throw new IllegalArgumentException(
                    "Invalid observation deadline"
            );
        }

        MarketObservation observation = new MarketObservation();

        observation.sessionTargetId = sessionTargetId;
        observation.baselineId = baselineId;
        observation.horizon = horizon;
        observation.referenceAt = referenceAt;
        observation.expectedObservationDate = expectedDate;
        observation.observationDeadlineDate = deadlineDate;
        observation.status = OutcomeStatus.pending;
        observation.exceptionStatus = OutcomeExceptionStatus.none;
        observation.observationDelaySessions = 0;
        observation.usableForAiEvidence = false;
        observation.unit = unit;

        return observation;
    }

    // 실제 시장 관측 결과 저장
    public void markReady(
            LocalDate sessionDate,
            OffsetDateTime asOfAt,
            BigDecimal value,
            BigDecimal baselineValue,
            int delaySessions,
            String sourceName,
            String sourceUrl
    ) {
        if (status != OutcomeStatus.pending) {
            throw new IllegalStateException(
                    "Observation already finalized"
            );
        }

        if (sessionDate == null
                || asOfAt == null
                || value == null
                || baselineValue == null
                || delaySessions < 0
                || sourceName == null
                || sourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid observation result"
            );
        }

        if (expectedObservationDate == null
                || observationDeadlineDate == null
                || sessionDate.isBefore(expectedObservationDate)
                || sessionDate.isAfter(observationDeadlineDate)) {
            throw new IllegalArgumentException(
                    "Observation outside allowed date range"
            );
        }

        BigDecimal calculatedChange =
                value.subtract(baselineValue);

        BigDecimal calculatedPercent = null;

        if (baselineValue.signum() != 0) {
            calculatedPercent = calculatedChange
                    .multiply(BigDecimal.valueOf(100))
                    .divide(
                            baselineValue,
                            6,
                            RoundingMode.HALF_UP
                    );
        }

        this.status = OutcomeStatus.ready;
        this.exceptionStatus = OutcomeExceptionStatus.none;

        this.observationSessionDate = sessionDate;
        this.observedAt = asOfAt;
        this.observedValue = value;
        this.observationDelaySessions = delaySessions;

        this.changeValue = calculatedChange;
        this.changePercent = calculatedPercent;

        this.usableForAiEvidence = true;

        this.dataSourceName = sourceName;
        this.dataSourceUrl = sourceUrl;
        this.unavailableReason = null;
    }

    // 관측 결과를 확보하지 못한 경우
    public void markUnavailable(
            OutcomeExceptionStatus exceptionStatus,
            String reason
    ) {
        if (status != OutcomeStatus.pending) {
            throw new IllegalStateException(
                    "Observation already finalized"
            );
        }

        if (exceptionStatus == null
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Unavailable exception status and reason required"
            );
        }

        this.status = OutcomeStatus.unavailable;
        this.exceptionStatus = exceptionStatus;

        this.observationSessionDate = null;
        this.observedAt = null;
        this.observedValue = null;

        this.observationDelaySessions = 0;

        this.changeValue = null;
        this.changePercent = null;

        this.usableForAiEvidence = false;

        this.dataSourceName = null;
        this.dataSourceUrl = null;
        this.unavailableReason = reason;
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