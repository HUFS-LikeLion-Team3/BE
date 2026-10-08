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

    public enum BaselineStatus {
        pending,
        ready,
        unavailable
    }
}