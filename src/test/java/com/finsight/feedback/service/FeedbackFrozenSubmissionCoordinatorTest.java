
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackFrozenSubmissionCoordinatorTest {

    @Mock LearningSessionRepository sessionRepository;
    @Mock AiFeedbackRepository feedbackRepository;
    @Mock AiFeedbackInputSnapshotRepository snapshotRepository;
    @Mock AiRagJobRepository jobRepository;
    @Mock FeedbackGenerationInputBuilder initialBuilder;
    @Mock AiRagFinalInputBuilder finalBuilder;
    @Mock AiRagContractMapper contractMapper;
    @Mock FeedbackFrozenSubmissionWriter frozenWriter;

    @InjectMocks
    FeedbackFrozenSubmissionCoordinator coordinator;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID submissionId = UUID.randomUUID();

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse("2026-11-05T10:00:00+09:00");

    private final String packageId =
            "sha256:" + "a".repeat(64);

    private final String requestId =
            "feedback:initial:" + sessionId + ":v1";

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(
                coordinator,
                "promptVersion",
                "feedback-p0-v1"
        );

        ReflectionTestUtils.setField(
                coordinator,
                "policyVersion",
                "feedback-policy-p0-v1"
        );

        ReflectionTestUtils.setField(
                coordinator,
                "packageId",
                packageId
        );
    }

    private void lockSession() {
        LearningSession session = mock(LearningSession.class);

        when(sessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));
    }

    private FeedbackGenerationInput input() {
        return new FeedbackGenerationInput(
                submittedAt,
                Map.of("news", Map.of("title", "Frozen news")),
                Map.of("targets", java.util.List.of()),
                Map.of(),
                Map.of(),
                Map.of(),
                "b".repeat(64),
                Set.of(),
                Map.of()
        );
    }

    private AiFeedback feedback(
            AiFeedback.FeedbackStage stage,
            String id,
            String prompt,
            String policy
    ) {
        AiFeedback feedback = AiFeedback.create(
                sessionId,
                stage,
                1,
                id,
                prompt,
                policy,
                submittedAt
        );

        ReflectionTestUtils.setField(
                feedback,
                "id",
                feedbackId
        );

        return feedback;
    }

    @Test
    void initialSubmissionFreezesInputBeforeCommit() {
        lockSession();

        FeedbackGenerationInput input = input();

        AiFeedback saved = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                requestId,
                "feedback-p0-v1",
                "feedback-policy-p0-v1"
        );

        when(initialBuilder.buildInitial(sessionId, submittedAt))
                .thenReturn(input);

        when(feedbackRepository.save(any(AiFeedback.class)))
                .thenReturn(saved);

        when(contractMapper.envelope(
                eq(saved),
                eq(input),
                eq(packageId),
                isNull()
        )).thenReturn("{\"frozen\":true}");

        coordinator.freezeInitial(sessionId, submittedAt);

        verify(frozenWriter).persist(
                eq(sessionId),
                eq(AiFeedback.FeedbackStage.INITIAL),
                eq(requestId),
                eq(submittedAt),
                eq("feedback-p0-v1"),
                eq("feedback-policy-p0-v1"),
                eq(packageId),
                same(input),
                eq("{\"frozen\":true}")
        );
    }

    @Test
    void duplicateFrozenInitialNeverRebuildsInput() {
        lockSession();

        AiFeedback existing = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                requestId,
                "feedback-p0-v1",
                "feedback-policy-p0-v1"
        );

        AiRagJob job = AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                requestId,
                packageId,
                "{\"frozen\":true}"
        );

        AiFeedbackInputSnapshot snapshot =
                mock(AiFeedbackInputSnapshot.class);

        when(feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.INITIAL,
                        1
                )).thenReturn(Optional.of(existing));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        when(snapshotRepository.findByAiFeedbackId(feedbackId))
                .thenReturn(Optional.of(snapshot));

        coordinator.freezeInitial(sessionId, submittedAt);

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                contractMapper,
                frozenWriter
        );

        verify(feedbackRepository, never())
                .save(any(AiFeedback.class));
    }

    @Test
    void finalInheritsInitialVersionAndWaitsForJobId() {
        lockSession();

        AiFeedback initial = feedback(
                AiFeedback.FeedbackStage.INITIAL,
                requestId,
                "news-market-explanation-v2.1",
                "feedback-policy-p0-v1"
        );

        AiRagJob initialJob = AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                requestId,
                packageId,
                "{\"frozen\":true}"
        );

        OffsetDateTime outcomeCutoff =
                submittedAt.plusDays(3);

        FeedbackGenerationInput finalInput = input();

        // FINAL 조회: 아직 기존 FINAL 작업이 없음.
        when(feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.FINAL,
                        1
                )).thenReturn(Optional.empty());

        // INITIAL 조회: 기존 INITIAL 작업 존재.
        when(feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.INITIAL,
                        1
                )).thenReturn(Optional.of(initial));

        when(jobRepository.findBySessionIdAndStage(
                sessionId, "initial"
        )).thenReturn(Optional.of(initialJob));

        when(finalBuilder.resolveCutoff(
                sessionId, submittedAt
        )).thenReturn(outcomeCutoff);

        when(finalBuilder.build(
                sessionId, outcomeCutoff
        )).thenReturn(finalInput);

        AiFeedback savedFinal = feedback(
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:" + sessionId + ":" + submissionId,
                "news-market-explanation-v2.1",
                "feedback-policy-p0-v1"
        );

        when(feedbackRepository.save(any(AiFeedback.class)))
                .thenReturn(savedFinal);

        coordinator.freezeFinal(
                sessionId,
                submissionId,
                submittedAt
        );

        verify(frozenWriter).persist(
                eq(sessionId),
                eq(AiFeedback.FeedbackStage.FINAL),
                eq("feedback:final:" + sessionId + ":" + submissionId),
                eq(outcomeCutoff),
                eq("news-market-explanation-v2.1"),
                eq("feedback-policy-p0-v1"),
                eq(packageId),
                same(finalInput),
                isNull()
        );

        verifyNoInteractions(contractMapper);
    }

    @Test
    void conflictingSubmissionNeverReplacesExistingRequest() {
        lockSession();

        AiFeedback existing = feedback(
                AiFeedback.FeedbackStage.FINAL,
                "feedback:final:" + sessionId + ":" + UUID.randomUUID(),
                "feedback-p0-v1",
                "feedback-policy-p0-v1"
        );

        when(feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.FINAL,
                        1
                )).thenReturn(Optional.of(existing));

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.freezeFinal(
                        sessionId,
                        submissionId,
                        submittedAt
                )
        );

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                contractMapper,
                frozenWriter
        );
    }
}
