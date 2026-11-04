package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * One idempotent delivery step for a durable AI/RAG job. This service is NOT
 * transactional: source writes and result capture use their own committed
 * transactions, then the external delivery POST is sent after DB commit.
 *
 * No model-generation call, submission replay or AI /retry is performed here.
 */
@Service
@RequiredArgsConstructor
public class AiRagDeliveryCoordinator {

    public enum Outcome { WAITING, REVIEW, BLOCKED, STORED, FAILED, RECONCILIATION }

    private final AiRagJobRepository jobRepository;
    private final AiFeedbackRepository feedbackRepository;
    private final AiRagJobClient client;
    private final AiRagJobStore jobStore;
    private final FeedbackGenerationService generationService;
    private final ApprovedFeedbackPersistenceService approvedPersistence;
    private final FeedbackGenerationPersistenceService persistence;

    private final JsonMapper json = JsonMapper.builder().build();

    public Outcome processOne(UUID feedbackId) {
        AiRagJob job = jobRepository.findById(feedbackId).orElseThrow();
        AiFeedback feedback = feedbackRepository.findById(feedbackId).orElseThrow();

        if ("stored".equals(job.getState())) {
            return Outcome.STORED;
        }
        if ("reconciliation_required".equals(job.getState())) {
            return Outcome.RECONCILIATION;
        }
        if (job.getJobId() == null) {
            return Outcome.WAITING; // Unaccepted jobs need a separate #25 recovery path.
        }
        if (feedback.getStatus() == AiFeedback.FeedbackStatus.failed) {
            return Outcome.FAILED; // Never invoke AI /retry automatically.
        }
        if (feedback.getStatus() == AiFeedback.FeedbackStatus.ready) {
            return reportStored(job); // DB commit succeeded; delivery POST may have failed.
        }
        if (feedback.getStatus() != AiFeedback.FeedbackStatus.generating) {
            return Outcome.WAITING;
        }

        // During awaiting_storage, GET /result returns 409. Reuse the exact
        // original response persisted BEFORE any validation; never regenerate.
        if (job.getApprovedResultJson() != null) {
            return storeCaptured(feedbackId);
        }

        Map<String, Object> status = client.getJobStatus(job.getJobId());
        try {
            verifyJobStatus(job, status);
        } catch (RuntimeException ex) {
            // An immutable identity discrepancy is not a transient poll failure.
            jobStore.uncertain(feedbackId, "ai_job_status_identity_mismatch");
            return Outcome.RECONCILIATION;
        }
        String aiStatus = (String) status.get("status");
        jobStore.updateStatus(feedbackId, aiStatus,
                status.get("ragSnapshotHash") instanceof String hash ? hash : null);

        return switch (aiStatus) {
            case "received", "preparing", "pending", "generating", "awaiting_initial" -> Outcome.WAITING;
            case "awaiting_review" -> Outcome.REVIEW;
            case "ready" -> fetchAndStore(feedbackId);
            case "awaiting_storage" -> {
                // A prior BE instance sent blocked but lost its raw result: manual
                // reconciliation is mandatory, because result GET now returns 409.
                jobStore.uncertain(feedbackId, "approved_result_missing_after_block");
                yield Outcome.RECONCILIATION;
            }
            case "reconciliation_required" -> {
                jobStore.uncertain(feedbackId, "ai_reconciliation_required");
                yield Outcome.RECONCILIATION;
            }
            case "failed" -> {
                persistence.fail(feedbackId, safeCode(status.get("failureCode"), "ai_job_failed"));
                yield Outcome.FAILED;
            }
            default -> {
                jobStore.uncertain(feedbackId, "ai_job_unknown_status");
                yield Outcome.RECONCILIATION;
            }
        };
    }

    private Outcome fetchAndStore(UUID feedbackId) {
        try {
            generationService.fetchApprovedResult(feedbackId);
        } catch (AiRagJobClient.AiRagHttpException ex) {
            if (ex.getStatusCode() == 409) {
                jobStore.uncertain(feedbackId, "approved_result_unavailable_after_ready");
                return Outcome.RECONCILIATION;
            }
            throw ex;
        } catch (IllegalArgumentException ex) {
            // fetchApprovedResult preserves malformed raw JSON in its own TX.
            // Without a verified resultHash there is no valid blocked receipt.
            jobStore.uncertain(feedbackId, "approved_result_invalid_json");
            return Outcome.RECONCILIATION;
        }
        return storeCaptured(feedbackId);
    }

    private Outcome storeCaptured(UUID feedbackId) {
        try {
            approvedPersistence.persistCaptured(feedbackId);
        } catch (RuntimeException ex) {
            // Race: a different worker completed the same transaction first.
            AiFeedback current = feedbackRepository.findById(feedbackId).orElseThrow();
            if (current.getStatus() == AiFeedback.FeedbackStatus.ready) {
                return reportStored(jobRepository.findById(feedbackId).orElseThrow());
            }

            AiRagJob captured = jobRepository.findById(feedbackId).orElseThrow();
            if (!hasCapturedHash(captured)) {
                jobStore.uncertain(feedbackId, "approved_result_missing_hash");
                return Outcome.RECONCILIATION;
            }
            String code = safeCode(ex.getMessage(), "approved_storage_blocked");
            jobStore.blocked(feedbackId, code);
            client.reportDelivery(captured.getJobId(), "blocked", captured.getResultHash(),
                    "BE storage blocked: " + code);
            return Outcome.BLOCKED;
        }
        // persistCaptured() committed before this HTTP call. If delivery fails,
        // the public ready row stays intact and the scheduler retries the POST.
        return reportStored(jobRepository.findById(feedbackId).orElseThrow());
    }

    private Outcome reportStored(AiRagJob job) {
        if (!hasCapturedHash(job)) {
            jobStore.uncertain(job.getFeedbackId(), "approved_delivery_missing_original");
            return Outcome.RECONCILIATION;
        }
        client.reportDelivery(job.getJobId(), "stored", job.getResultHash(),
                "be-issue26:stored:" + job.getResultHash());
        jobStore.stored(job.getFeedbackId(), job.getJobId(), job.getResultHash());
        return Outcome.STORED;
    }

    private boolean hasCapturedHash(AiRagJob job) {
        return job.getJobId() != null && job.getApprovedResultJson() != null
                && job.getResultHash() != null
                && job.getResultHash().matches("[0-9a-f]{64}");
    }

    private void verifyJobStatus(AiRagJob job, Map<String, Object> status) {
        if (job.getRequestJson() == null || status == null) {
            throw new IllegalStateException("ai_job_frozen_request_or_status_missing");
        }
        Map<String, Object> request;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = json.readValue(job.getRequestJson(), Map.class);
            request = decoded;
        } catch (Exception ex) {
            throw new IllegalStateException("ai_job_frozen_request_invalid", ex);
        }
        requireSame(status, "jobId", job.getJobId().toString());
        requireSame(status, "feedbackId", job.getFeedbackId().toString());
        requireSame(status, "sessionId", job.getSessionId().toString());
        requireSame(status, "beRequestId", job.getBeRequestId());
        requireSame(status, "stage", job.getStage());
        requireSame(status, "packageId", job.getPackageId());
        requireSame(status, "beInputHash", request.get("beInputHash"));
        if (!(status.get("status") instanceof String name) || name.isBlank()) {
            throw new IllegalStateException("ai_job_status_missing");
        }
    }

    private void requireSame(Map<String, Object> response, String field, Object expected) {
        if (!(expected instanceof String value) || value.isBlank()
                || !value.equals(response.get(field))) {
            throw new IllegalStateException("ai_job_immutable_identity_mismatch:" + field);
        }
    }

    private String safeCode(Object raw, String fallback) {
        if (raw instanceof String code && code.matches("[a-z][a-z0-9_]{2,99}")) {
            return code;
        }
        return fallback;
    }
}
