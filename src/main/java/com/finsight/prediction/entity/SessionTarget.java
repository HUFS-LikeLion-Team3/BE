package com.finsight.prediction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "session_targets",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_session_targets_session_market",
                        columnNames = {
                                "learning_session_id",
                                "market_target_id"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_session_targets_session_sort_order",
                        columnNames = {
                                "learning_session_id",
                                "sort_order"
                        }
                )
        }
)
@Getter
@NoArgsConstructor
public class SessionTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "learning_session_id", nullable = false)
    private UUID learningSessionId;

    @Column(name = "market_target_id", nullable = false)
    private UUID marketTargetId;

    @Column(name = "is_selected", nullable = false)
    private boolean isSelected = true;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public static SessionTarget create(
            UUID learningSessionId,
            UUID marketTargetId,
            Integer sortOrder
    ) {
        SessionTarget sessionTarget = new SessionTarget();
        sessionTarget.learningSessionId = learningSessionId;
        sessionTarget.marketTargetId = marketTargetId;
        sessionTarget.isSelected = true;
        sessionTarget.sortOrder = sortOrder;
        return sessionTarget;
    }

    public void select(Integer sortOrder) {
        this.isSelected = true;
        this.sortOrder = sortOrder;
    }

    public void deselect() {
        this.isSelected = false;
        this.sortOrder = null;
    }
}
