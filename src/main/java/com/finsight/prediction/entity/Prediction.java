package com.finsight.prediction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "predictions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_predictions_target_horizon", columnNames = {"session_target_id", "horizon"})
})
@Getter
@NoArgsConstructor
public class Prediction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_target_id", nullable = false)
    private UUID sessionTargetId;

    @Column(nullable = false, columnDefinition = "SMALLINT")
    private Short horizon;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction")
    private Direction direction;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum Direction { up, down, neutral }

    public static Prediction create(UUID sessionTargetId, int horizon) {
        Prediction prediction = new Prediction();
        prediction.sessionTargetId = sessionTargetId;
        prediction.horizon = (short) horizon;
        return prediction;
    }

    public void update(boolean directionProvided, Direction newDirection,
                       boolean reasonProvided, String newReason) {
        if (submittedAt != null) {
            throw new IllegalStateException("Submitted prediction cannot be edited");
        }
        if (directionProvided) {
            this.direction = newDirection;
        }
        if (reasonProvided) {
            this.reason = newReason;
        }
    }

    public void markSubmitted(OffsetDateTime submittedAt) {
        if (this.submittedAt != null) {
            throw new IllegalStateException("Prediction already submitted");
        }
        this.submittedAt = submittedAt;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
