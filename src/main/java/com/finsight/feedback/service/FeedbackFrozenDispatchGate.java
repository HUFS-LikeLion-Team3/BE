
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedbackFrozenDispatchGate {

    private static final int VERSION = 1;

    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final AiRagJobRepository jobRepository;

    @Transactional(readOnly = true)
    public boolean canDispatchInitial(UUID sessionId) {
        return canDispatch(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:" + sessionId + ":v1"
        );
    }

    @Transactional(readOnly = true)
    public boolean canDispatchFinal(
            UUID sessionId,
            UUID reflectionSubmissionId
    ) {
        if (reflectionSubmissionId == null) {
            return false;
        }

        return canDispatch(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:" + sessionId
                        + ":" + reflectionSubmissionId
        );
    }

    private boolean canDispatch(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String expectedRequestId
    ) {
        if (sessionId == null) {
            return false;
        }

        AiFeedback feedback = feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        stage,
                        VERSION
                )
                .orElse(null);

        if (feedback == null) {
            log.warn(
                    "Frozen dispatch skipped: feedback missing. "
                            + "sessionId={}, stage={}",
                    sessionId,
                    stage
            );
            return false;
        }

        if (!expectedRequestId.equals(feedback.getRequestId())) {
            log.warn(
                    "Frozen dispatch skipped: request conflict. "
                            + "feedbackId={}",
                    feedback.getId()
            );
            return false;
        }

        if (feedback.getFeedbackStage() != stage
                || !sessionId.equals(feedback.getLearningSessionId())) {
            return false;
        }

        if (feedback.getStatus()
                != AiFeedback.FeedbackStatus.pending
                && feedback.getStatus()
                != AiFeedback.FeedbackStatus.generating) {
            return false;
        }

        UUID feedbackId = feedback.getId();

        AiRagJob job = jobRepository
                .findById(feedbackId)
                .orElse(null);

        if (job == null) {
            log.error(
                    "Frozen dispatch blocked: frozen_job_missing. "
                            + "feedbackId={}",
                    feedbackId
            );
            return false;
        }

        if (!feedbackId.equals(job.getFeedbackId())
                || !sessionId.equals(job.getSessionId())
                || !stage.getValue().equals(job.getStage())
                || !expectedRequestId.equals(job.getBeRequestId())) {
            log.error(
                    "Frozen dispatch blocked: job_identity_conflict. "
                            + "feedbackId={}",
                    feedbackId
            );
            return false;
        }

        if (job.getJobId() != null) {
            // Already accepted: delivery polling owns it.
            return false;
        }

        String state = job.getState();

        if ("stored".equals(state)
                || "blocked".equals(state)
                || "failed".equals(state)
                || "reconciliation_required".equals(state)) {
            return false;
        }

        String requestJson = job.getRequestJson();

        if (stage == AiFeedback.FeedbackStage.INITIAL) {
            if (requestJson == null || requestJson.isBlank()) {
                log.error(
                        "Frozen dispatch blocked: "
                                + "initial_request_missing. "
                                + "feedbackId={}",
                        feedbackId
                );
                return false;
            }
        } else if ((requestJson == null || requestJson.isBlank())
                && !"awaiting_initial_job".equals(state)) {
            return false;
        }

        if (snapshotRepository
                .findByAiFeedbackId(feedbackId)
                .isEmpty()) {
            log.error(
                    "Frozen dispatch blocked: "
                            + "original_snapshot_missing. "
                            + "feedbackId={}",
                    feedbackId
            );
            return false;
        }

        return true;
    }
}
