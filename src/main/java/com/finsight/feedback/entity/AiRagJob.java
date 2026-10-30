package com.finsight.feedback.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "ai_rag_jobs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ai_rag_jobs_request",
                        columnNames = "be_request_id"
                ),
                @UniqueConstraint(
                        name = "uk_ai_rag_jobs_session_stage",
                        columnNames = {"session_id", "stage"}
                )
        }
)
@Getter
@NoArgsConstructor
public class AiRagJob {

    @Id
    @Column(name = "feedback_id", nullable = false)
    private UUID feedbackId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    @Column(
            name = "be_request_id",
            nullable = false,
            length = 240
    )
    private String beRequestId;

    @Column(
            name = "package_id",
            nullable = false,
            length = 96
    )
    private String packageId;

    @Lob
    @Column(name = "request_json")
    private String requestJson;

    @Column(name = "job_id", unique = true)
    private UUID jobId;

    @Column(name = "rag_snapshot_hash", length = 64)
    private String ragSnapshotHash;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "ai_status", length = 40)
    private String aiStatus;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static AiRagJob create(
            UUID feedbackId,
            UUID sessionId,
            String stage,
            String requestId,
            String packageId,
            String requestJson
    ) {
        AiRagJob job = new AiRagJob();

        job.feedbackId = feedbackId;
        job.sessionId = sessionId;
        job.stage = stage;
        job.beRequestId = requestId;
        job.packageId = packageId;
        job.requestJson = requestJson;
        job.state = requestJson == null
                ? "awaiting_initial_job"
                : "frozen";

        return job;
    }

    public void freeze(String requestJson) {
        if (this.requestJson != null
                && !this.requestJson.equals(requestJson)) {
            throw new IllegalStateException(
                    "request_input_conflict"
            );
        }

        if (this.requestJson == null) {
            this.requestJson = requestJson;
        }

        if ("awaiting_initial_job".equals(state)) {
            state = "frozen";
        }
    }

    public void accepted(UUID jobId, String aiStatus) {
        if (this.jobId != null
                && !this.jobId.equals(jobId)) {
            throw new IllegalStateException(
                    "AI job ID mismatch"
            );
        }

        this.jobId = jobId;
        this.aiStatus = aiStatus;
        this.state = "accepted";
        this.failureCode = null;
    }

    public void uncertain(String code) {
        this.state = "reconciliation_required";
        this.failureCode = code;
    }

    public void updateStatus(
            String status,
            String ragHash
    ) {
        this.aiStatus = status;

        if (ragHash != null && !ragHash.isBlank()) {
            this.ragSnapshotHash = ragHash;
        }
    }

    @PrePersist
    void beforeInsert() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }

        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}