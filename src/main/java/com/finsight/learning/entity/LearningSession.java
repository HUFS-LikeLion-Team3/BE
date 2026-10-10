package com.finsight.learning.entity;

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
        name = "learning_sessions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_learning_sessions_user_news",
                        columnNames = {"user_id", "news_id"}
                )
        }
)
@Getter
@NoArgsConstructor
public class LearningSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "news_id", nullable = false)
    private UUID newsId;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false)
    private SessionType sessionType = SessionType.live;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LearningStatus status = LearningStatus.drafting;

    @Column(name = "results_revealed_at")
    private OffsetDateTime resultsRevealedAt;

    @Column(name = "saved_at")
    private OffsetDateTime savedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

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

    public void markSaved() {
        this.savedAt = OffsetDateTime.now();
    }

    /** Freeze all prediction inputs and move to baseline collection state. */
    public void markSubmitted(OffsetDateTime submittedAt) {
        if (this.status != LearningStatus.drafting) {
            throw new IllegalStateException("Session already submitted");
        }
        this.submittedAt = submittedAt;
        this.status = LearningStatus.baseline_pending;
    }

    public enum SessionType {
        live,
        replay
    }

    public static LearningSession create(UUID userId, UUID newsId) {
        LearningSession session = new LearningSession();
        session.userId = userId;
        session.newsId = newsId;
        return session;
    }

    public enum LearningStatus {
        drafting,
        baseline_pending,
        observing,
        reflection_pending,
        completed
    }
}
