
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiRagPendingRecoverySchedulerTest {

    @Mock
    EntityManager entityManager;

    @Mock(answer = Answers.RETURNS_SELF)
    TypedQuery<UUID> query;

    @Mock
    AiFeedbackRepository feedbackRepository;

    @Mock
    AiRagJobRepository jobRepository;

    @Mock
    FeedbackGenerationService generationService;

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse(
                    "2026-11-05T10:00:00+09:00"
            );

    private AiRagPendingRecoveryScheduler scheduler() {
        return new AiRagPendingRecoveryScheduler(
                entityManager,
                feedbackRepository,
                jobRepository,
                generationService
        );
    }

    private void candidates(List<UUID> feedbackIds) {
        when(entityManager.createQuery(
                anyString(),
                eq(UUID.class)
        )).thenReturn(query);

        when(query.getResultList())
                .thenReturn(feedbackIds);
    }

    private AiFeedback pending(
            UUID sessionId,
            AiFeedback.FeedbackStage stage
    ) {
        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.pending);

        when(feedback.getFeedbackStage())
                .thenReturn(stage);

        when(feedback.getLearningSessionId())
                .thenReturn(sessionId);

        return feedback;
    }

    private AiRagJob frozenInitial(
            UUID feedbackId,
            UUID sessionId
    ) {
        return AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                "feedback:initial:" + sessionId + ":v1",
                "sha256:" + "a".repeat(64),
                "{\"frozen\":true}"
        );
    }

    @Test
    void recoversExistingFrozenInitialWithoutRebuilding() {

        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = pending(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        when(feedback.getInputCutoffAt())
                .thenReturn(submittedAt);

        AiRagJob job = frozenInitial(
                feedbackId,
                sessionId
        );

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        scheduler().recover();

        verify(generationService).generateInitial(
                sessionId,
                submittedAt
        );

        verifyNoMoreInteractions(generationService);
    }

    @Test
    void missingFrozenJobIsNeverRecreatedFromCurrentDatabase() {

        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = pending(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.empty());

        scheduler().recover();

        verifyNoInteractions(generationService);
    }

    @Test
    void missingFinalJobIsNotRecreatedFromCurrentDatabase() {

        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = pending(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.empty());

        scheduler().recover();

        verifyNoInteractions(generationService);
    }

    @Test
    void frozenFinalJobIsResumedWithoutRebuilding() {

        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = pending(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        AiRagJob job = mock(AiRagJob.class);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        scheduler().recover();

        verify(generationService).resumeFinal(sessionId);

        verify(generationService, never())
                .generateFinal(any(), any(), any());
    }

    @Test
    void acceptedJobIsNeverResubmitted() {

        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID aiJobId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.generating);

        AiRagJob job = mock(AiRagJob.class);

        when(job.getJobId())
                .thenReturn(aiJobId);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        scheduler().recover();

        verifyNoInteractions(generationService);
    }

    @Test
    void reconciliationRequiredJobIsNeverAutomaticallyResubmitted() {

        UUID feedbackId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.pending);

        AiRagJob job = mock(AiRagJob.class);

        when(job.getState())
                .thenReturn("reconciliation_required");

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        scheduler().recover();

        verifyNoInteractions(generationService);
    }

    @Test
    void readyFeedbackIsNotRegenerated() {

        UUID feedbackId = UUID.randomUUID();

        candidates(List.of(feedbackId));

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.ready);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        scheduler().recover();

        verifyNoInteractions(
                jobRepository,
                generationService
        );
    }

    @Test
    void oneRecoveryFailureDoesNotBlockNextCandidate() {

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        UUID firstSession = UUID.randomUUID();
        UUID secondSession = UUID.randomUUID();

        candidates(List.of(firstId, secondId));

        AiFeedback first = pending(
                firstSession,
                AiFeedback.FeedbackStage.INITIAL
        );

        AiFeedback second = pending(
                secondSession,
                AiFeedback.FeedbackStage.INITIAL
        );

        when(first.getInputCutoffAt())
                .thenReturn(submittedAt);

        when(second.getInputCutoffAt())
                .thenReturn(submittedAt);

        when(feedbackRepository.findById(firstId))
                .thenReturn(Optional.of(first));

        when(feedbackRepository.findById(secondId))
                .thenReturn(Optional.of(second));

        when(jobRepository.findById(firstId))
                .thenReturn(Optional.of(
                        frozenInitial(firstId, firstSession)
                ));

        when(jobRepository.findById(secondId))
                .thenReturn(Optional.of(
                        frozenInitial(secondId, secondSession)
                ));

        doThrow(new IllegalStateException("provider_unavailable"))
                .when(generationService)
                .generateInitial(firstSession, submittedAt);

        scheduler().recover();

        verify(generationService).generateInitial(
                firstSession,
                submittedAt
        );

        verify(generationService).generateInitial(
                secondSession,
                submittedAt
        );
    }

    @Test
    void scanContinuesBeyondFirstTwoHundredCandidates() {

        UUID firstId = UUID.randomUUID();
        UUID nextPageId = UUID.randomUUID();

        UUID firstSession = UUID.randomUUID();
        UUID nextSession = UUID.randomUUID();

        List<UUID> firstPage =
                java.util.Collections.nCopies(200, firstId);

        when(entityManager.createQuery(
                anyString(),
                eq(UUID.class)
        )).thenReturn(query);

        when(query.getResultList())
                .thenReturn(
                        firstPage,
                        List.of(nextPageId)
                );

        AiFeedback first = pending(
                firstSession,
                AiFeedback.FeedbackStage.INITIAL
        );

        AiFeedback next = pending(
                nextSession,
                AiFeedback.FeedbackStage.INITIAL
        );

        when(first.getInputCutoffAt())
                .thenReturn(submittedAt);

        when(next.getInputCutoffAt())
                .thenReturn(submittedAt);

        when(feedbackRepository.findById(firstId))
                .thenReturn(Optional.of(first));

        when(feedbackRepository.findById(nextPageId))
                .thenReturn(Optional.of(next));

        when(jobRepository.findById(firstId))
                .thenReturn(Optional.of(
                        frozenInitial(firstId, firstSession)
                ));

        when(jobRepository.findById(nextPageId))
                .thenReturn(Optional.of(
                        frozenInitial(nextPageId, nextSession)
                ));

        AiRagPendingRecoveryScheduler scheduler = scheduler();

        scheduler.recover();
        scheduler.recover();

        verify(query).setFirstResult(0);
        verify(query).setFirstResult(200);

        verify(generationService).generateInitial(
                nextSession,
                submittedAt
        );
    }

    @Test
    void skipsWhenNoRecoveryCandidateExists() {

        candidates(List.of());

        scheduler().recover();

        verifyNoInteractions(
                feedbackRepository,
                jobRepository,
                generationService
        );
    }
}
