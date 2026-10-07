package com.finsight.prediction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "predictions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_predictions_target_horizon",
                        columnNames = {
                                "session_target_id",
                                "horizon"
                        }
                )
        }
)
@Getter
@NoArgsConstructor
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Column(nullable = false)
    private Integer horizon;

    @Enumerated(EnumType.STRING)
    @Column
    private Direction direction;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public enum Direction {
        up,
        down,
        neutral
    }

    public static Prediction create(
            UUID sessionTargetId,
            Integer horizon,
            Direction direction,
            String reason
    ) {
        Prediction prediction = new Prediction();
        prediction.sessionTargetId = sessionTargetId;
        prediction.horizon = horizon;
        prediction.direction = direction;
        prediction.reason = reason;
        return prediction;
    }

    public void update(
            Direction direction,
            String reason
    ) {
        this.direction = direction;
        this.reason = reason;
    }

    public void submit(OffsetDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }
}
