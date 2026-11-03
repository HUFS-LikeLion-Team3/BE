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
    @Column(name = "request_json", columnDefinition = "LONGTEXT")
    private String requestJson;

    @Column(name = "job_id", unique = true)
    private UUID jobId;

    @Column(name = "rag_snapshot_hash", length = 64)
    private String ragSnapshotHash;

    // Unmodified UTF-8 JSON response from the approved result endpoint.
    // Preserved before any document/chunk validation and never overwritten.
    @Lob
    @Column(name = "approved_result_json", columnDefinition = "LONGTEXT")
    private String approvedResultJson;

    @Column(name = "result_hash", length = 128)
    private String resultHash;

    @Column(name = "approved_result_captured_at")
    private OffsetDateTime approvedResultCapturedAt;

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

    /**
     * The approved result is immutable for a given AI job. A second read may
     * re-use the same payload; replacing it with a different result is forbidden.
     * A missing resultHash is left null for downstream validation to block.
     */
    public void captureApprovedResult(
            UUID expectedJobId,
            String responseJson,
            String hash
    ) {
        if (jobId == null || !jobId.equals(expectedJobId)) {
            throw new IllegalStateException("approved_result_job_mismatch");
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new IllegalArgumentException("approved_result_empty");
        }
        if (approvedResultJson != null && !approvedResultJson.equals(responseJson)) {
            throw new IllegalStateException("approved_result_conflict");
        }
        if (resultHash != null && hash != null && !resultHash.equals(hash)) {
            throw new IllegalStateException("approved_result_hash_conflict");
        }
        if (approvedResultJson == null) {
            approvedResultJson = responseJson;
            approvedResultCapturedAt = OffsetDateTime.now();
        }
        if (resultHash == null && hash != null && !hash.isBlank()) {
            resultHash = hash;
        }
    }

    public void markDeliveryBlocked(String reason) {
        if ("stored".equals(state)) {
            return; // A delayed blocked notification must never regress stored.
        }
        if (approvedResultJson == null) {
            throw new IllegalStateException("approved_result_not_captured");
        }
        state = "blocked";
        failureCode = reason;
    }

    public void markDeliveryStored(UUID expectedJobId, String expectedResultHash) {
        if (jobId == null || !jobId.equals(expectedJobId)
                || approvedResultJson == null || resultHash == null
                || !resultHash.equals(expectedResultHash)) {
            throw new IllegalStateException("approved_delivery_identity_mismatch");
        }
        state = "stored";
        failureCode = null;
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