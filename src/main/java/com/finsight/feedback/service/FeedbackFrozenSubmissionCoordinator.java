
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import com.finsight.learning.repository.LearningSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackFrozenSubmissionCoordinator {

    private static final int VERSION = 1;

    private final LearningSessionRepository sessionRepository;
    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final AiRagJobRepository jobRepository;
    private final FeedbackGenerationInputBuilder initialBuilder;
    private final AiRagFinalInputBuilder finalBuilder;
    private final AiRagContractMapper contractMapper;
    private final FeedbackFrozenSubmissionWriter frozenWriter;

    @Value("${finsight.ai.prompt-version:feedback-p0-v1}")
    private String promptVersion;

    @Value("${finsight.ai.policy-version:feedback-policy-p0-v1}")
    private String policyVersion;

    @Value("${AI_RAG_PACKAGE_ID:}")
    private String packageId;

    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeInitial(
            UUID sessionId,
            OffsetDateTime submittedAt
    ) {
        freeze(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:" + sessionId + ":v1",
                submittedAt
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeFinal(
            UUID sessionId,
            UUID reflectionSubmissionId,
            OffsetDateTime completedAt
    ) {
        Objects.requireNonNull(reflectionSubmissionId);

        freeze(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:" + sessionId
                        + ":" + reflectionSubmissionId,
                completedAt
        );
    }

    private void freeze(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime submittedAt
    ) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(submittedAt);

        // All writes participate in the original submission transaction.
        sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "learning_session_missing"
                ));

        Optional<AiFeedback> existing =
                feedbackRepository
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                stage,
                                VERSION
                        );

        if (existing.isPresent()) {
            handleExisting(
                    existing.get(),
                    sessionId,
                    stage,
                    requestId
            );
            return;
        }

        String frozenPromptVersion = promptVersion;
        String frozenPolicyVersion = policyVersion;
        String frozenPackageId = packageId;

        OffsetDateTime cutoff = submittedAt;
        UUID initialJobId = null;

        if (stage == AiFeedback.FeedbackStage.FINAL) {

            AiFeedback initial = feedbackRepository
                    .findByLearningSessionIdAndFeedbackStageAndVersion(
                            sessionId,
                            AiFeedback.FeedbackStage.INITIAL,
                            VERSION
                    )
                    .orElse(null);

            if (initial == null) {
                recordManualReconciliation(
                        sessionId,
                        stage,
                        requestId,
                        cutoff,
                        frozenPromptVersion,
                        frozenPolicyVersion,
                        frozenPackageId,
                        "original_initial_snapshot_missing"
                );
                return;
            }

            frozenPromptVersion = initial.getPromptVersion();
            frozenPolicyVersion = initial.getPolicyVersion();

            AiRagJob initialJob = jobRepository
                    .findBySessionIdAndStage(
                            sessionId,
                            "initial"
                    )
                    .orElse(null);

            if (initialJob != null) {
                frozenPackageId = initialJob.getPackageId();
                initialJobId = initialJob.getJobId();
            }

            try {
                cutoff = finalBuilder.resolveCutoff(
                        sessionId,
                        submittedAt
                );
            } catch (RuntimeException ex) {
                logFreezeFailure(
                        sessionId,
                        stage,
                        "final_cutoff_freeze_failed",
                        ex
                );

                recordManualReconciliation(
                        sessionId,
                        stage,
                        requestId,
                        submittedAt,
                        frozenPromptVersion,
                        frozenPolicyVersion,
                        frozenPackageId,
                        "final_cutoff_freeze_failed"
                );
                return;
            }
        }

        if (frozenPackageId == null
                || !frozenPackageId.matches(
                "sha256:[0-9a-f]{64}"
        )) {

            recordManualReconciliation(
                    sessionId,
                    stage,
                    requestId,
                    cutoff,
                    frozenPromptVersion,
                    frozenPolicyVersion,
                    frozenPackageId,
                    "ai_rag_package_not_configured"
            );
            return;
        }

        FeedbackGenerationInput input;

        try {
            input = stage == AiFeedback.FeedbackStage.INITIAL
                    ? initialBuilder.buildInitial(
                    sessionId,
                    cutoff
            )
                    : finalBuilder.build(
                    sessionId,
                    cutoff
            );
        } catch (RuntimeException ex) {
            String code =
                    stage == AiFeedback.FeedbackStage.INITIAL
                            ? "initial_input_freeze_failed"
                            : "final_input_freeze_failed";

            logFreezeFailure(
                    sessionId,
                    stage,
                    code,
                    ex
            );

            recordManualReconciliation(
                    sessionId,
                    stage,
                    requestId,
                    cutoff,
                    frozenPromptVersion,
                    frozenPolicyVersion,
                    frozenPackageId,
                    code
            );
            return;
        }

        if (input == null
                || input.inputHash() == null
                || !input.inputHash().matches("[0-9a-f]{64}")) {

            recordManualReconciliation(
                    sessionId,
                    stage,
                    requestId,
                    cutoff,
                    frozenPromptVersion,
                    frozenPolicyVersion,
                    frozenPackageId,
                    "frozen_input_invalid"
            );
            return;
        }

        AiFeedback feedback = feedbackRepository.save(
                AiFeedback.create(
                        sessionId,
                        stage,
                        VERSION,
                        requestId,
                        frozenPromptVersion,
                        frozenPolicyVersion,
                        cutoff
                )
        );

        String requestJson;

        if (stage == AiFeedback.FeedbackStage.FINAL
                && initialJobId == null) {

            // Preserve FINAL input; submission waits for INITIAL jobId.
            requestJson = null;

        } else {
            try {
                requestJson = contractMapper.envelope(
                        feedback,
                        input,
                        frozenPackageId,
                        initialJobId
                );
            } catch (RuntimeException ex) {
                logFreezeFailure(
                        sessionId,
                        stage,
                        "frozen_request_serialization_failed",
                        ex
                );

                recordManualReconciliation(
                        sessionId,
                        stage,
                        requestId,
                        cutoff,
                        frozenPromptVersion,
                        frozenPolicyVersion,
                        frozenPackageId,
                        "frozen_request_serialization_failed"
                );
                return;
            }

            if (requestJson == null
                    || requestJson.isBlank()) {

                recordManualReconciliation(
                        sessionId,
                        stage,
                        requestId,
                        cutoff,
                        frozenPromptVersion,
                        frozenPolicyVersion,
                        frozenPackageId,
                        "frozen_request_empty"
                );
                return;
            }
        }

        // Do not catch persistence errors here.
        // A database failure may have already marked the
        // surrounding transaction rollback-only.
        frozenWriter.persist(
                sessionId,
                stage,
                requestId,
                cutoff,
                frozenPromptVersion,
                frozenPolicyVersion,
                frozenPackageId,
                input,
                requestJson
        );

        log.debug(
                "AI submission frozen. feedbackId={}, stage={}",
                feedback.getId(),
                stage
        );
    }

    /**
     * Called only for known preparation failures.
     *
     * No AI request is submitted.
     * No current-DB snapshot reconstruction is attempted.
     *
     * This method does not swallow database persistence failures.
     */
    private void recordManualReconciliation(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime cutoff,
            String frozenPromptVersion,
            String frozenPolicyVersion,
            String frozenPackageId,
            String failureCode
    ) {
        AiFeedback feedback = feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        stage,
                        VERSION
                )
                .orElseGet(() -> feedbackRepository.save(
                        AiFeedback.create(
                                sessionId,
                                stage,
                                VERSION,
                                requestId,
                                frozenPromptVersion,
                                frozenPolicyVersion,
                                cutoff
                        )
                ));

        if (!requestId.equals(feedback.getRequestId())) {
            throw new IllegalStateException(
                    "feedback_or_session_stage_conflict"
            );
        }

        if (feedback.getStatus()
                == AiFeedback.FeedbackStatus.ready) {
            return;
        }

        AiRagJob job = jobRepository
                .findById(feedback.getId())
                .orElse(null);

        if (job == null) {
            String diagnosticPackageId =
                    frozenPackageId == null
                            || frozenPackageId.isBlank()
                            ? "unconfigured"
                            : frozenPackageId;

            job = AiRagJob.create(
                    feedback.getId(),
                    sessionId,
                    stage.getValue(),
                    requestId,
                    diagnosticPackageId,
                    null
            );

            job.uncertain(failureCode);
            jobRepository.save(job);

        } else if (job.getJobId() == null
                && job.getRequestJson() == null
                && !"stored".equals(job.getState())) {

            job.uncertain(failureCode);
        }

        if (feedback.getStatus()
                == AiFeedback.FeedbackStatus.pending) {
            feedback.markGenerating();
        }

        log.error(
                "AI frozen input unavailable; manual reconciliation "
                        + "required. feedbackId={}, sessionId={}, "
                        + "stage={}, failureCode={}",
                feedback.getId(),
                sessionId,
                stage,
                failureCode
        );
    }

    private void logFreezeFailure(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String failureCode,
            RuntimeException ex
    ) {
        log.warn(
                "AI input freeze failed. sessionId={}, stage={}, "
                        + "failureCode={}, exceptionType={}",
                sessionId,
                stage,
                failureCode,
                ex.getClass().getSimpleName()
        );
    }

    private void handleExisting(
            AiFeedback feedback,
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId
    ) {
        if (!requestId.equals(feedback.getRequestId())) {
            throw new IllegalStateException(
                    "feedback_or_session_stage_conflict"
            );
        }

        UUID feedbackId = feedback.getId();

        AiRagJob job = jobRepository
                .findById(feedbackId)
                .orElse(null);

        AiFeedbackInputSnapshot snapshot =
                snapshotRepository
                        .findByAiFeedbackId(feedbackId)
                        .orElse(null);

        if (job != null && snapshot != null) {
            if (stage == AiFeedback.FeedbackStage.INITIAL
                    && job.getRequestJson() == null
                    && job.getJobId() == null
                    && !"reconciliation_required".equals(
                    job.getState()
            )) {

                job.uncertain(
                        "original_frozen_request_missing"
                );
            }

            return;
        }

        if (feedback.getStatus()
                == AiFeedback.FeedbackStatus.ready) {
            return;
        }

        recordManualReconciliation(
                sessionId,
                stage,
                requestId,
                feedback.getInputCutoffAt(),
                feedback.getPromptVersion(),
                feedback.getPolicyVersion(),
                packageId,
                "original_frozen_input_missing"
        );
    }
}
