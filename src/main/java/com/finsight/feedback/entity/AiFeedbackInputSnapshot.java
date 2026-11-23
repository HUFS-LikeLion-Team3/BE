package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "ai_feedback_input_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_feedback_snapshot_feedback",
                columnNames = "ai_feedback_id"
        )
)
@Getter
@NoArgsConstructor
public class AiFeedbackInputSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ai_feedback_id", nullable = false)
    private UUID aiFeedbackId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, columnDefinition = "json")
    private Map<String, Object> requestSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prediction_snapshot", nullable = false, columnDefinition = "json")
    private Map<String, Object> predictionSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "market_baseline_snapshot", columnDefinition = "json")
    private Map<String, Object> marketBaselineSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "market_result_snapshot", columnDefinition = "json")
    private Map<String, Object> marketResultSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reflection_snapshot", columnDefinition = "json")
    private Map<String, Object> reflectionSnapshot;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static AiFeedbackInputSnapshot create(
            UUID aiFeedbackId,
            Map<String, Object> requestSnapshot,
            Map<String, Object> predictionSnapshot,
            Map<String, Object> marketBaselineSnapshot,
            Map<String, Object> marketResultSnapshot,
            Map<String, Object> reflectionSnapshot,
            String inputHash
    ) {
        AiFeedbackInputSnapshot snapshot =
                new AiFeedbackInputSnapshot();

        snapshot.aiFeedbackId = aiFeedbackId;
        snapshot.requestSnapshot = requestSnapshot;
        snapshot.predictionSnapshot = predictionSnapshot;
        snapshot.marketBaselineSnapshot = marketBaselineSnapshot;
        snapshot.marketResultSnapshot = marketResultSnapshot;
        snapshot.reflectionSnapshot = reflectionSnapshot;
        snapshot.inputHash = inputHash;
        snapshot.createdAt = OffsetDateTime.now();

        return snapshot;
    }
}