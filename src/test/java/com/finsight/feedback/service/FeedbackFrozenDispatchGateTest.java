
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackFrozenDispatchGateTest {

    @Mock
    AiFeedbackRepository feedbackRepository;

    @Mock
    AiFeedbackInputSnapshotRepository snapshotRepository;

    @Mock
    AiRagJobRepository jobRepository;

    @InjectMocks
    FeedbackFrozenDispatchGate gate;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    private final String packageId =
            "sha256:" + "a".repeat(64);

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse(
                    "2026-11-05T10:00:00+09:00"
            );

    private String initialRequestId() {
        return "feedback:initial:" + sessionId + ":v1";
    }

    private String finalRequestId() {
        return "feedback:final:" + sessionId
                + ":" + submissionId;
    }

    private AiFeedback feedback(
            AiFeedback.FeedbackStage stage,
            String requestId
    ) {
        AiFeedback feedback = AiFeedback.create(
                sessionId,
                stage,
                1,
                requestId,
                "feedback-p0-v1",
                "feedback-policy-p0-v1",
                submittedAt
        );

        ReflectionTestUtils.setField(
                feedback,
                "id",
                feedbackId
        );

        return feedback;
    }

    private void found(
            AiFeedback.FeedbackStage stage,
            AiFeedback feedback
    ) {
        when(feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        stage,
                        1
                ))
                .thenReturn(Optional.of(feedback));
    }

    private AiRagJob initialJob() {
        return AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                initialRequestId(),
                packageId,
                "{\"frozen\":true}"
        );
    }

    private void snapshotExists() {
        when(snapshotRepository.findByAiFeedbackId(feedbackId))
                .thenReturn(Optional.of(
                        mock(AiFeedbackInputSnapshot.class)
                ));
    }

    @Test
    void pendingIntentWithoutJobIsNeverDispatched() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        assertFalse(gate.canDispatchInitial(sessionId));

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    void frozenJobWithoutSnapshotIsNotDispatched() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(initialJob()));

        assertFalse(gate.canDispatchInitial(sessionId));
    }

    @Test
    void fullyFrozenInitialIsEligible() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(initialJob()));

        snapshotExists();

        assertTrue(gate.canDispatchInitial(sessionId));
    }

    @Test
    void reconciliationRequiredJobIsNeverDispatched() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        AiRagJob job = initialJob();
        job.uncertain("provider_completion_unknown");

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        assertFalse(gate.canDispatchInitial(sessionId));

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    void acceptedJobBelongsToDeliveryPolling() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        AiRagJob job = initialJob();
        job.accepted(UUID.randomUUID(), "received");

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        assertFalse(gate.canDispatchInitial(sessionId));

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    void readyFeedbackIsNotDispatched() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                initialRequestId()
        );

        feedback.markGenerating();
        feedback.markReady(
                "model-v1",
                "approved",
                false,
                submittedAt
        );

        found(AiFeedback.FeedbackStage.INITIAL, feedback);

        assertFalse(gate.canDispatchInitial(sessionId));

        verifyNoInteractions(
                jobRepository,
                snapshotRepository
        );
    }

    @Test
    void frozenFinalWaitingForInitialJobIsEligibleForResume() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.FINAL,
                finalRequestId()
        );

        AiRagJob job = AiRagJob.create(
                feedbackId,
                sessionId,
                "final",
                finalRequestId(),
                packageId,
                null
        );

        found(AiFeedback.FeedbackStage.FINAL, feedback);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        snapshotExists();

        assertTrue(
                gate.canDispatchFinal(sessionId, submissionId)
        );
    }

    @Test
    void conflictingFinalSubmissionIsRejected() {
        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:"
                        + sessionId
                        + ":"
                        + UUID.randomUUID()
        );

        found(AiFeedback.FeedbackStage.FINAL, feedback);

        assertFalse(
                gate.canDispatchFinal(sessionId, submissionId)
        );

        verifyNoInteractions(
                jobRepository,
                snapshotRepository
        );
    }
}
