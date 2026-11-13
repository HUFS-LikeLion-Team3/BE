
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiFeedbackRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class FeedbackSubmissionIntentService {

    private static final int VERSION = 1;

    private final AiFeedbackRepository feedbackRepository;
    private final String promptVersion;
    private final String policyVersion;

    public FeedbackSubmissionIntentService(
            AiFeedbackRepository feedbackRepository,
            @Value("${finsight.ai.prompt-version:feedback-p0-v1}")
            String promptVersion,
            @Value("${finsight.ai.policy-version:feedback-policy-p0-v1}")
            String policyVersion
    ) {
        this.feedbackRepository = feedbackRepository;
        this.promptVersion = promptVersion;
        this.policyVersion = policyVersion;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInitial(
            UUID sessionId,
            OffsetDateTime submittedAt
    ) {
        record(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:" + sessionId + ":v1",
                submittedAt
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFinal(
            UUID sessionId,
            UUID reflectionSubmissionId,
            OffsetDateTime completedAt
    ) {
        Objects.requireNonNull(reflectionSubmissionId);

        record(
                sessionId,
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:" + sessionId
                        + ":" + reflectionSubmissionId,
                completedAt
        );
    }

    private void record(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            String requestId,
            OffsetDateTime cutoff
    ) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(cutoff);

        var existing = feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        stage,
                        VERSION
                );

        if (existing.isPresent()) {
            // Keep the original request immutable.
            // Conflicting FINAL submissions never replace it.
            return;
        }

        AiFeedback intent = AiFeedback.create(
                sessionId,
                stage,
                VERSION,
                requestId,
                promptVersion,
                policyVersion,
                cutoff
        );

        feedbackRepository.save(intent);
    }
}
