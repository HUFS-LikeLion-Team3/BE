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
    private final AiRagContractMapper contractMapper;
    private final AiRagJobClient ragClient;

    @Value("${finsight.ai.prompt-version:feedback-p0-v1}")
    private String promptVersion;

    @Value("${finsight.ai.policy-version:feedback-policy-p0-v1}")
    private String policyVersion;

    @Value("${AI_RAG_PACKAGE_ID:}")
    private String packageId;

    public void generateInitial(
            UUID sessionId,
            OffsetDateTime submittedAt
    ) {
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
        OffsetDateTime cutoff;

        try {
            cutoff = finalBuilder.resolveCutoff(
                    sessionId,
                    completedAt
            );

        } catch (Exception ex) {
            log.warn(
                    "FINAL cutoff unavailable; preserving final submission intent. sessionId={}",
                    sessionId
            );

            cutoff = completedAt;
        }

        createOrResume(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:"
                        + sessionId
                        + ":"
                        + reflectionSubmissionId,
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

            AiFeedback feedback =
                    feedbackRepository
                            .findById(feedbackId)
                            .orElseThrow();

            if (feedback.getStatus()
                    == AiFeedback.FeedbackStatus.ready) {
                return;
            }

            AiRagJob job = jobRepository
                    .findById(feedbackId)
                    .orElse(null);

            if (job == null) {
                FeedbackGenerationInput frozen =
                        stage == AiFeedback.FeedbackStage.INITIAL
                                ? initialBuilder.buildInitial(
                                sessionId,
                                cutoff
                        )
                                : finalBuilder.build(
                                sessionId,
                                cutoff
                        );

                persistence.saveSnapshot(
                        feedbackId,
                        frozen
                );

                UUID initialJobId =
                        stage == AiFeedback.FeedbackStage.FINAL
                                ? initialJobId(sessionId)
                                : null;

                String request =
                        stage == AiFeedback.FeedbackStage.FINAL
                                && initialJobId == null
                                ? null
                                : contractMapper.envelope(
                                feedback,
                                frozen,
                                packageId,
                                initialJobId
                        );

                job = jobStore.ensure(
                        feedbackId,
                        sessionId,
                        stage.getValue(),
                        feedback.getRequestId(),
                        packageId,
                        request
                );
            }

            persistence.claim(feedbackId);

            submitFrozen(job.getFeedbackId());

        } catch (Exception ex) {
            log.error(
                    "AI/RAG job preparation failed. sessionId={}, stage={}",
                    sessionId,
                    stage,
                    ex
            );

            if (feedbackId != null
                    && jobRepository.existsById(feedbackId)) {
                jobStore.uncertain(
                        feedbackId,
                        errorCode(ex)
                );

            } else if (feedbackId != null) {
                try {
                    persistence.fail(
                            feedbackId,
                            errorCode(ex)
                    );

                } catch (Exception secondary) {
                    log.error(
                            "Feedback failure recording failed",
                            secondary
                    );
                }
            }
        }
    }

    public void resumeFinal(UUID sessionId) {
        AiRagJob job = jobRepository
                .findBySessionIdAndStage(
                        sessionId,
                        "final"
                )
                .orElse(null);

        if (job == null || job.getJobId() != null) {
            return;
        }

        if (job.getRequestJson() == null) {
            UUID firstJob = initialJobId(sessionId);

            if (firstJob == null) {
                return;
            }

            AiFeedback feedback =
                    feedbackRepository
                            .findById(job.getFeedbackId())
                            .orElseThrow();

            AiFeedbackInputSnapshot snapshot =
                    snapshotRepository
                            .findByAiFeedbackId(feedback.getId())
                            .orElseThrow();

            Map<String, Object> request =
                    snapshot.getRequestSnapshot();

            Object cutoffValue =
                    request.get("outcomeCutoffAt");

            if (!(cutoffValue instanceof String)) {
                throw new IllegalStateException(
                        "Frozen FINAL outcomeCutoffAt is missing"
                );
            }

            FeedbackGenerationInput frozen =
                    new FeedbackGenerationInput(
                            OffsetDateTime.parse(
                                    (String) cutoffValue
                            ),
                            request,
                            snapshot.getPredictionSnapshot(),
                            snapshot.getMarketBaselineSnapshot(),
                            snapshot.getMarketResultSnapshot(),
                            snapshot.getReflectionSnapshot(),
                            snapshot.getInputHash(),
                            java.util.Set.of(),
                            java.util.Map.of()
                    );

            String json = contractMapper.envelope(
                    feedback,
                    frozen,
                    job.getPackageId(),
                    firstJob
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

        submitFrozen(job.getFeedbackId());
    }

    private UUID initialJobId(UUID sessionId) {
        return jobRepository
                .findBySessionIdAndStage(
                        sessionId,
                        "initial"
                )
                .map(AiRagJob::getJobId)
                .orElse(null);
    }

    private void submitFrozen(UUID feedbackId) {
        AiRagJob job = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (job.getJobId() != null
                || job.getRequestJson() == null) {
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

            receipt = ragClient.submitFrozenJob(
                    job.getRequestJson()
            );
        }

        Object rawJobId = receipt.get("jobId");

        if (!(rawJobId instanceof String id)) {
            throw new IllegalStateException(
                    "AI receipt has no jobId"
            );
        }

        UUID aiJobId = UUID.fromString(id);

        String status = String.valueOf(
                receipt.getOrDefault(
                        "status",
                        "received"
                )
        );

        jobStore.accepted(
                feedbackId,
                aiJobId,
                status
        );

        if ("initial".equals(job.getStage())) {
            try {
                resumeFinal(job.getSessionId());

            } catch (Exception e) {
                log.error(
                        "Deferred FINAL submission failed. sessionId={}",
                        job.getSessionId(),
                        e
                );
            }
        }
    }

    public Map<String, Object> fetchJobStatus(
            UUID feedbackId
    ) {
        AiRagJob stored = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (stored.getJobId() == null) {
            throw new IllegalStateException(
                    "AI job not accepted"
            );
        }

        Map<String, Object> status =
                ragClient.getJobStatus(
                        stored.getJobId()
                );

        jobStore.updateStatus(
                feedbackId,
                String.valueOf(
                        status.get("status")
                ),
                status.get("ragSnapshotHash")
                        instanceof String hash
                        ? hash
                        : null
        );

        return status;
    }

    public Map<String, Object> fetchApprovedResult(
            UUID feedbackId
    ) {
        AiRagJob stored = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        if (stored.getJobId() == null) {
            throw new IllegalStateException(
                    "AI job not accepted"
            );
        }

        return ragClient.getApprovedResult(
                stored.getJobId()
        );
    }

    private String errorCode(Exception ex) {
        if (ex instanceof
                AiRagJobClient.AiRagHttpException http) {
            return http.getErrorCode();
        }

        String message = ex.getMessage();

        if ("initial_submission_changed".equals(message)
                || "initial_snapshot_missing".equals(message)) {
            return message;
        }

        return "provider_completion_unknown";
    }
}