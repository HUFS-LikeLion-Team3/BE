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
        name = "session_target_baselines",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_session_target_baselines_session_target",
                        columnNames = "session_target_id"
                )
        }
)
@Getter
@NoArgsConstructor
public class SessionTargetBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BaselineStatus status = BaselineStatus.pending;

    @Column(name = "expected_baseline_date", nullable = false)
    private LocalDate expectedBaselineDate;

    @Column(name = "baseline_deadline_date", nullable = false)
    private LocalDate baselineDeadlineDate;

    @Column(name = "baseline_session_date")
    private LocalDate baselineSessionDate;

    @Column(name = "baseline_at")
    private OffsetDateTime baselineAt;

    @Column(name = "baseline_value", precision = 20, scale = 6)
    private BigDecimal baselineValue;

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

    // T0 최초 수집 대상 생성
    public static SessionTargetBaseline createPending(
            UUID sessionTargetId,
            LocalDate expectedDate,
            LocalDate deadlineDate,
            String unit
    ) {
        if (sessionTargetId == null
                || expectedDate == null
                || deadlineDate == null
                || deadlineDate.isBefore(expectedDate)
                || unit == null
                || unit.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid baseline initialization"
            );
        }

        SessionTargetBaseline baseline = new SessionTargetBaseline();

        baseline.sessionTargetId = sessionTargetId;
        baseline.status = BaselineStatus.pending;
        baseline.expectedBaselineDate = expectedDate;
        baseline.baselineDeadlineDate = deadlineDate;
        baseline.unit = unit;

        return baseline;
    }

    // 확정된 T0 시장 데이터 저장
    public void markReady(
            LocalDate sessionDate,
            OffsetDateTime asOfAt,
            BigDecimal value,
            String sourceName,
            String sourceUrl
    ) {
        if (status != BaselineStatus.pending) {
            throw new IllegalStateException(
                    "Baseline already finalized"
            );
        }

        if (sessionDate == null
                || sessionDate.isBefore(expectedBaselineDate)
                || sessionDate.isAfter(baselineDeadlineDate)
                || asOfAt == null
                || value == null
                || sourceName == null
                || sourceName.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid baseline result"
            );
        }

        this.status = BaselineStatus.ready;
        this.baselineSessionDate = sessionDate;
        this.baselineAt = asOfAt;
        this.baselineValue = value;
        this.dataSourceName = sourceName;
        this.dataSourceUrl = sourceUrl;
        this.unavailableReason = null;
    }

    // 수집 기한 종료 등으로 T0 확정 불가
    public void markUnavailable(String reason) {
        if (status != BaselineStatus.pending) {
            throw new IllegalStateException(
                    "Baseline already finalized"
            );
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Unavailable reason required"
            );
        }

        this.status = BaselineStatus.unavailable;
        this.baselineSessionDate = null;
        this.baselineAt = null;
        this.baselineValue = null;
        this.dataSourceName = null;
        this.dataSourceUrl = null;
        this.unavailableReason = reason;
    }

    public enum BaselineStatus {
        pending,
        ready,
        unavailable
    }
}