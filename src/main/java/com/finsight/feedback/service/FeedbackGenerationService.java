
package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackGenerationService {

    private static final int VERSION = 1;

    private final FeedbackGenerationInputBuilder initialBuilder;
    private final AiRagFinalInputBuilder finalBuilder;
    private final FeedbackGenerationPersistenceService persistence;
    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final AiRagJobRepository jobRepository;
    private final AiRagJobStore jobStore;
    private final AiRagDispatchLeaseService dispatchLease;
    private final AiRagContractMapper contractMapper;
    private final AiRagJobClient ragClient;
    private final ApprovedFeedbackPersistenceService approvedPersistence;

    @Value("${finsight.ai.prompt-version:feedback-p0-v1}")
    private String promptVersion;

    @Value("${finsight.ai.policy-version:feedback-policy-p0-v1}")
    private String policyVersion;

    @Value("${AI_RAG_PACKAGE_ID:}")
    private String packageId;

    public void generateInitial(UUID sessionId, OffsetDateTime submittedAt) {
        createOrResume(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:" + sessionId + ":v1",
                submittedAt
        );
    }

    public void generateFinal(
            UUID sessionId,
            UUID reflectionSubmissionId,
            OffsetDateTime completedAt
    ) {
        String requestId =
                "feedback:final:" + sessionId + ":" + reflectionSubmissionId;

        // Duplicate FINAL events must reuse the original frozen work.
        AiRagJob existing = jobRepository
                .findBySessionIdAndStage(sessionId, "final")
                .orElse(null);

        if (existing != null) {
            if (!requestId.equals(existing.getBeRequestId())) {
                log.warn(
                        "Conflicting FINAL event ignored. sessionId={}",
                        sessionId
                );
                return;
            }

            resumeFrozen(existing.getFeedbackId());
            return;
        }

        OffsetDateTime cutoff;

        try {
            cutoff = finalBuilder.resolveCutoff(sessionId, completedAt);
        } catch (Exception ex) {
            log.warn(
                    "FINAL cutoff unavailable on first attempt. sessionId={}",
                    sessionId
            );
            cutoff = completedAt;
        }

        createOrResume(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                requestId,
                cutoff
        );
    }

    private void createOrResume(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime cutoff
    ) {
        UUID feedbackId = null;

        try {
            feedbackId = persistence.prepare(
                    sessionId,
                    stage,
                    VERSION,
                    requestId,
                    promptVersion,
                    policyVersion,
                    cutoff
            );

            AiFeedback feedback = feedbackRepository
                    .findById(feedbackId)
                    .orElseThrow();

            if (feedback.getStatus() == AiFeedback.FeedbackStatus.ready
                    || feedback.getStatus() == AiFeedback.FeedbackStatus.failed) {
                return;
            }

            AiRagJob job = jobRepository
                    .findById(feedbackId)
                    .orElse(null);

            if (job == null) {
                FeedbackGenerationInput frozen = loadOrFreezeInput(
                        feedbackId,
                        sessionId,
                        stage,
                        cutoff
                );

                UUID firstJobId =
                        stage == AiFeedback.FeedbackStage.FINAL
                                ? initialJobId(sessionId)
                                : null;

                String requestJson =
                        stage == AiFeedback.FeedbackStage.FINAL
                                && firstJobId == null
                                ? null
                                : contractMapper.envelope(
                                feedback,
                                frozen,
                                packageId,
                                firstJobId
                        );

                job = jobStore.ensure(
                        feedbackId,
                        sessionId,
                        stage.getValue(),
                        feedback.getRequestId(),
                        packageId,
                        requestJson
                );
            }

            // Already accepted jobs belong to the #26 polling/delivery path.
            if (job.getJobId() != null
                    || "reconciliation_required".equals(job.getState())
                    || "stored".equals(job.getState())) {
                return;
            }

            // FINAL waits until INITIAL has an accepted AI job ID.
            if (job.getRequestJson() == null) {
                return;
            }

            dispatchFrozenIfOwned(feedbackId);

        } catch (Exception ex) {
            handlePreparationFailure(
                    feedbackId,
                    sessionId,
                    stage,
                    ex
            );
        }
    }

    private FeedbackGenerationInput loadOrFreezeInput(
            UUID feedbackId,
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            OffsetDateTime cutoff
    ) {
        var saved = snapshotRepository.findByAiFeedbackId(feedbackId);

        if (saved.isPresent()) {
            return fromSnapshot(saved.get(), stage);
        }

        FeedbackGenerationInput built =
                stage == AiFeedback.FeedbackStage.INITIAL
                        ? initialBuilder.buildInitial(sessionId, cutoff)
                        : finalBuilder.build(sessionId, cutoff);

        try {
            persistence.saveSnapshot(feedbackId, built);
        } catch (RuntimeException ex) {
            // A concurrent event may have saved the first snapshot.
            // Reuse that snapshot instead of replacing it.
            if (snapshotRepository.findByAiFeedbackId(feedbackId).isEmpty()) {
                throw ex;
            }
        }

        return fromSnapshot(
                snapshotRepository.findByAiFeedbackId(feedbackId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "input_snapshot_missing"
                                )
                        ),
                stage
        );
    }

    private FeedbackGenerationInput fromSnapshot(
            AiFeedbackInputSnapshot snapshot,
            AiFeedback.FeedbackStage stage
    ) {
        Map<String, Object> request = snapshot.getRequestSnapshot();

        String cutoffKey =
                stage == AiFeedback.FeedbackStage.FINAL
                        ? "outcomeCutoffAt"
                        : "inputCutoffAt";

        if (request == null
                || !(request.get(cutoffKey) instanceof String cutoff)) {
            throw new IllegalStateException("input_snapshot_missing");
        }

        return new FeedbackGenerationInput(
                OffsetDateTime.parse(cutoff),
                request,
                snapshot.getPredictionSnapshot(),
                snapshot.getMarketBaselineSnapshot(),
                snapshot.getMarketResultSnapshot(),
                snapshot.getReflectionSnapshot(),
                snapshot.getInputHash(),
                Set.of(),
                Map.of()
        );
    }

    private void resumeFrozen(UUID feedbackId) {
        try {
            AiFeedback feedback = feedbackRepository
                    .findById(feedbackId)
                    .orElseThrow();

            if (feedback.getStatus() == AiFeedback.FeedbackStatus.ready
                    || feedback.getStatus() == AiFeedback.FeedbackStatus.failed) {
                return;
            }

            AiRagJob job = jobRepository
                    .findById(feedbackId)
                    .orElseThrow();

            if (job.getJobId() != null
                    || job.getRequestJson() == null
                    || "reconciliation_required".equals(job.getState())
                    || "stored".equals(job.getState())) {
                return;
            }

            dispatchFrozenIfOwned(feedbackId);

        } catch (Exception ex) {
            handlePreparationFailure(feedbackId, null, null, ex);
        }
    }

    private void handlePreparationFailure(
            UUID feedbackId,
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            Exception ex
    ) {
        log.warn(
                "AI/RAG preparation or submission deferred. "
                        + "feedbackId={}, sessionId={}, stage={}, error={}",
                feedbackId,
                sessionId,
                stage,
                ex.getClass().getSimpleName()
        );

        // Temporary failures must not change an existing generating job to failed.
        if (feedbackId != null && jobRepository.existsById(feedbackId)) {

            if (ex instanceof AiRagJobClient.AiRagHttpException http
                    && http.getStatusCode() == 409) {
                // Remote immutable request conflict requires reconciliation.
                // Local duplicate event conflicts do not invalidate the job.
                jobStore.uncertain(feedbackId, http.getErrorCode());
            }

            return;
        }

        // Recovery for feedback without a snapshot/job is handled by the
        // subsequent durable-intent recovery work.
    }

    public void resumeFinal(UUID sessionId) {
        AiRagJob job = jobRepository
                .findBySessionIdAndStage(sessionId, "final")
                .orElse(null);

        if (job == null
                || job.getJobId() != null
                || "reconciliation_required".equals(job.getState())) {
            return;
        }

        if (job.getRequestJson() == null) {
            UUID firstJobId = initialJobId(sessionId);

            if (firstJobId == null) {
                return;
            }

            AiFeedback feedback = feedbackRepository
                    .findById(job.getFeedbackId())
                    .orElseThrow();

            AiFeedbackInputSnapshot snapshot = snapshotRepository
                    .findByAiFeedbackId(feedback.getId())
                    .orElseThrow();

            String json = contractMapper.envelope(
                    feedback,
                    fromSnapshot(
                            snapshot,
                            AiFeedback.FeedbackStage.FINAL
                    ),
                    job.getPackageId(),
                    firstJobId
            );

            jobStore.ensure(
                    job.getFeedbackId(),
                    sessionId,
                    "final",
                    job.getBeRequestId(),
                    job.getPackageId(),
                    json
            );
        }

        resumeFrozen(job.getFeedbackId());
    }

    private UUID initialJobId(UUID sessionId) {
        return jobRepository
                .findBySessionIdAndStage(sessionId, "initial")
                .map(AiRagJob::getJobId)
                .orElse(null);
    }

    /** A durable lease excludes concurrent BE instances without holding a DB
     * transaction open while the external AI HTTP request is in flight. */
    private void dispatchFrozenIfOwned(UUID feedbackId) {
        UUID owner = dispatchLease.tryClaim(feedbackId);
        if (owner == null) {
            return;
        }
        try {
            submitFrozen(feedbackId);
        } finally {
            // A crash leaves the lease for restart recovery; ordinary failures
            // release it so the next scheduled scan can query the same request.
            try {
                dispatchLease.release(feedbackId, owner);
            } catch (RuntimeException ex) {
                log.warn("Dispatch lease release deferred. feedbackId={}", feedbackId, ex);
            }
        }
    }

    private void submitFrozen(UUID feedbackId) {
        AiRagJob job = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (job.getJobId() != null
                || job.getRequestJson() == null
                || "reconciliation_required".equals(job.getState())) {
            return;
        }

        Map<String, Object> receipt;

        try {
            receipt = ragClient.findByBeRequestId(
                    job.getBeRequestId()
            );
        } catch (AiRagJobClient.AiRagHttpException ex) {
            if (ex.getStatusCode() != 404) {
                throw ex;
            }

            // Only a confirmed 404 permits resubmitting the same frozen body.
            receipt = ragClient.submitFrozenJob(job.getRequestJson());
        }

        Object rawJobId = receipt.get("jobId");

        if (!(rawJobId instanceof String id)) {
            throw new IllegalStateException("AI receipt has no jobId");
        }

        if (receipt.get("beRequestId") != null
                && !job.getBeRequestId().equals(receipt.get("beRequestId"))) {
            throw new IllegalStateException("request_input_conflict");
        }

        if (receipt.get("feedbackId") != null
                && !job.getFeedbackId().toString()
                .equals(receipt.get("feedbackId"))) {
            throw new IllegalStateException(
                    "feedback_or_session_stage_conflict"
            );
        }

        UUID aiJobId = UUID.fromString(id);

        String status = String.valueOf(
                receipt.getOrDefault("status", "received")
        );

        jobStore.accepted(feedbackId, aiJobId, status);

        if ("initial".equals(job.getStage())) {
            try {
                resumeFinal(job.getSessionId());
            } catch (Exception ex) {
                log.warn(
                        "Deferred FINAL resume failed. sessionId={}",
                        job.getSessionId(),
                        ex
                );
            }
        }
    }

    public Map<String, Object> fetchJobStatus(UUID feedbackId) {
        AiRagJob stored = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (stored.getJobId() == null) {
            throw new IllegalStateException("AI job not accepted");
        }

        Map<String, Object> status = ragClient.getJobStatus(
                stored.getJobId()
        );

        jobStore.updateStatus(
                feedbackId,
                String.valueOf(status.get("status")),
                status.get("ragSnapshotHash") instanceof String hash
                        ? hash
                        : null
        );

        return status;
    }

    public Map<String, Object> fetchApprovedResult(UUID feedbackId) {
        AiRagJob stored = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (stored.getJobId() == null) {
            throw new IllegalStateException("AI job not accepted");
        }

        if (stored.getApprovedResultJson() != null) {
            return ragClient.parseResponse(
                    stored.getApprovedResultJson()
            );
        }

        String rawJson = ragClient.getApprovedResultRaw(
                stored.getJobId()
        );

        Map<String, Object> response;

        try {
            response = ragClient.parseResponse(rawJson);
        } catch (RuntimeException ex) {
            // Preserve even malformed original JSON for diagnosis.
            jobStore.captureApprovedResult(
                    feedbackId,
                    stored.getJobId(),
                    rawJson,
                    null
            );
            throw ex;
        }

        String hash =
                response.get("resultHash") instanceof String value
                        && !value.isBlank()
                        ? value
                        : null;

        jobStore.captureApprovedResult(
                feedbackId,
                stored.getJobId(),
                rawJson,
                hash
        );

        return response;
    }

    public boolean storeApprovedResult(UUID feedbackId) {
        fetchApprovedResult(feedbackId);
        return approvedPersistence.persistCaptured(feedbackId);
    }
}
