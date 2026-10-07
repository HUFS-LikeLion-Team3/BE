package com.finsight.feedback.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(
        name = "ai_feedbacks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_feedback_session_stage_version",
                        columnNames = {
                                "learning_session_id",
                                "feedback_stage",
                                "version"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_ai_feedback_request_id",
                        columnNames = "request_id"
                )
        }
)
@Getter
@NoArgsConstructor
public class AiFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "learning_session_id", nullable = false)
    private UUID learningSessionId;

    @Convert(converter = FeedbackStageConverter.class)
    @Column(name = "feedback_stage", nullable = false)
    private FeedbackStage feedbackStage;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private FeedbackStatus status = FeedbackStatus.pending;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "is_latest", nullable = false)
    private Boolean isLatest = true;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "flow_summary", columnDefinition = "TEXT")
    private String flowSummary;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "input_cutoff_at", nullable = false)
    private OffsetDateTime inputCutoffAt;

    @Column(name = "insufficient_evidence", nullable = false)
    private Boolean insufficientEvidence = false;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public enum FeedbackStage {
        INITIAL("initial"),
        FINAL("final");

        private final String value;

        FeedbackStage(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static FeedbackStage fromValue(String value) {
            return Arrays.stream(values())
                    .filter(stage -> stage.value.equals(value))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Unknown feedback stage: " + value
                            )
                    );
        }
    }

    public enum FeedbackStatus {
        pending,
        generating,
        ready,
        failed
    }
}