
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeedbackFrozenSubmissionWriter {

    private static final int VERSION = 1;

    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final AiRagJobRepository jobRepository;

    /**
     * Persist feedback, immutable input snapshot, and RAG job
     * in the caller's existing submission transaction.
     *
     * This method must never perform external HTTP calls.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID persist(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime cutoff,
            String promptVersion,
            String policyVersion,
            String packageId,
            FeedbackGenerationInput input,
            String frozenRequestJson
    ) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(cutoff);
        Objects.requireNonNull(promptVersion);
        Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(packageId);
        Objects.requireNonNull(input);

        if (requestId.isBlank()
                || promptVersion.isBlank()
                || policyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "feedback_submission_metadata_missing"
            );
        }

        if (!packageId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "feedback_package_id_invalid"
            );
        }

        if (input.inputHash() == null
                || !input.inputHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "feedback_input_hash_invalid"
            );
        }

        if (stage == AiFeedback.FeedbackStage.INITIAL
                && (frozenRequestJson == null
                || frozenRequestJson.isBlank())) {
            throw new IllegalArgumentException(
                    "initial_frozen_request_missing"
            );
        }

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
                                promptVersion,
                                policyVersion,
                                cutoff
                        )
                ));

        if (!requestId.equals(feedback.getRequestId())
                || !sessionId.equals(feedback.getLearningSessionId())
                || stage != feedback.getFeedbackStage()) {
            throw new IllegalStateException(
                    "feedback_or_session_stage_conflict"
            );
        }

        UUID feedbackId = feedback.getId();

        var existingSnapshot =
                snapshotRepository.findByAiFeedbackId(feedbackId);

        var existingJob = jobRepository.findById(feedbackId);

        // An already-running feedback must never have its
        // missing original input reconstructed from current DB data.
        if (feedback.getStatus()
                != AiFeedback.FeedbackStatus.pending
                && (existingSnapshot.isEmpty()
                || existingJob.isEmpty())) {
            throw new IllegalStateException(
                    "original_frozen_input_missing"
            );
        }

        if (existingSnapshot.isPresent()) {
            if (!input.inputHash().equals(
                    existingSnapshot.get().getInputHash())) {
                throw new IllegalStateException(
                        "request_input_conflict"
                );
            }
        } else {
            snapshotRepository.save(
                    AiFeedbackInputSnapshot.create(
                            feedbackId,
                            input.requestSnapshot(),
                            input.predictionSnapshot(),
                            input.marketBaselineSnapshot(),
                            input.marketResultSnapshot(),
                            input.reflectionSnapshot(),
                            input.inputHash()
                    )
            );
        }

        if (existingJob.isPresent()) {
            AiRagJob job = existingJob.get();

            if (!sessionId.equals(job.getSessionId())
                    || !stage.getValue().equals(job.getStage())
                    || !requestId.equals(job.getBeRequestId())
                    || !packageId.equals(job.getPackageId())) {
                throw new IllegalStateException(
                        "request_input_conflict"
                );
            }

            if ("reconciliation_required".equals(job.getState())) {
                throw new IllegalStateException(
                        "manual_reconciliation_required"
                );
            }

            if (frozenRequestJson != null) {
                job.freeze(frozenRequestJson);
            }

            return feedbackId;
        }

        jobRepository.save(
                AiRagJob.create(
                        feedbackId,
                        sessionId,
                        stage.getValue(),
                        requestId,
                        packageId,
                        frozenRequestJson
                )
        );

        return feedbackId;
    }
}
