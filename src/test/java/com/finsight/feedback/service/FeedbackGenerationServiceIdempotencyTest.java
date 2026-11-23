
package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackGenerationServiceIdempotencyTest {

    @Mock FeedbackGenerationInputBuilder initialBuilder;
    @Mock AiRagFinalInputBuilder finalBuilder;
    @Mock FeedbackGenerationPersistenceService persistence;
    @Mock AiFeedbackRepository feedbackRepository;
    @Mock AiFeedbackInputSnapshotRepository snapshotRepository;
    @Mock AiRagJobRepository jobRepository;
    @Mock AiRagJobStore jobStore;
    @Mock AiRagDispatchLeaseService dispatchLease;
    @Mock AiRagContractMapper contractMapper;
    @Mock AiRagJobClient ragClient;
    @Mock ApprovedFeedbackPersistenceService approvedPersistence;

    @InjectMocks FeedbackGenerationService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID aiJobId = UUID.randomUUID();

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse("2026-11-05T10:00:00+09:00");

    private final String requestId =
            "feedback:initial:" + sessionId + ":v1";

    private void givenPreparedGeneratingFeedback() {
        when(persistence.prepare(
                eq(sessionId),
                eq(AiFeedback.FeedbackStage.INITIAL),
                eq(1),
                eq(requestId),
                any(),
                any(),
                eq(submittedAt)
        )).thenReturn(feedbackId);

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.generating);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));
    }

    private AiRagJob frozenJob(UUID jobId) {
        AiRagJob job = mock(AiRagJob.class);

        lenient().when(job.getFeedbackId()).thenReturn(feedbackId);
        lenient().when(job.getSessionId()).thenReturn(sessionId);
        lenient().when(job.getBeRequestId()).thenReturn(requestId);
        lenient().when(job.getStage()).thenReturn("initial");
        lenient().when(job.getJobId()).thenReturn(jobId);
        lenient().when(job.getRequestJson())
                .thenReturn("{\"frozen\":true}");
        lenient().when(job.getState())
                .thenReturn(jobId == null ? "frozen" : "accepted");

        return job;
    }

    @Test
    void duplicateEventForAcceptedJobDoesNotRebuildOrResubmit() {
        givenPreparedGeneratingFeedback();

        // Mock 객체를 먼저 생성하여 중첩 스터빙 방지
        AiRagJob existingJob = frozenJob(aiJobId);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(existingJob));

        service.generateInitial(sessionId, submittedAt);

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                snapshotRepository,
                contractMapper,
                ragClient,
                jobStore
        );

        verify(persistence, never()).claim(any());
    }

    @Test
    void existingFrozenJobIsRecoveredByRequestIdWithoutRebuildingInput() {
        givenPreparedGeneratingFeedback();
        when(dispatchLease.tryClaim(feedbackId)).thenReturn(UUID.randomUUID());

        // Mock 객체를 먼저 생성하여 중첩 스터빙 방지
        AiRagJob existingJob = frozenJob(null);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(existingJob));

        when(ragClient.findByBeRequestId(requestId))
                .thenReturn(Map.of(
                        "jobId", aiJobId.toString(),
                        "status", "received"
                ));

        service.generateInitial(sessionId, submittedAt);

        verify(ragClient).findByBeRequestId(requestId);
        verify(ragClient, never()).submitFrozenJob(anyString());

        verify(jobStore).accepted(feedbackId, aiJobId, "received");

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                snapshotRepository,
                contractMapper
        );
    }

    @Test
    void onlyHttp404AllowsRepostOfExactStoredJson() {
        givenPreparedGeneratingFeedback();
        when(dispatchLease.tryClaim(feedbackId)).thenReturn(UUID.randomUUID());

        // Mock 객체를 먼저 생성하여 중첩 스터빙 방지
        AiRagJob existingJob = frozenJob(null);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(existingJob));

        when(ragClient.findByBeRequestId(requestId))
                .thenThrow(
                        new AiRagJobClient.AiRagHttpException(
                                404,
                                "not_found"
                        )
                );

        when(ragClient.submitFrozenJob("{\"frozen\":true}"))
                .thenReturn(Map.of(
                        "jobId", aiJobId.toString(),
                        "status", "received"
                ));

        service.generateInitial(sessionId, submittedAt);

        verify(ragClient).submitFrozenJob("{\"frozen\":true}");
        verify(jobStore).accepted(feedbackId, aiJobId, "received");

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                snapshotRepository,
                contractMapper
        );
    }

    @Test
    void temporaryProviderFailureNeverMarksExistingGeneratingAsFailed() {
        givenPreparedGeneratingFeedback();
        when(dispatchLease.tryClaim(feedbackId)).thenReturn(UUID.randomUUID());

        // Mock 객체를 먼저 생성하여 중첩 스터빙 방지
        AiRagJob existingJob = frozenJob(null);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(existingJob));

        when(jobRepository.existsById(feedbackId))
                .thenReturn(true);

        when(ragClient.findByBeRequestId(requestId))
                .thenThrow(
                        new AiRagJobClient.AiRagHttpException(
                                503,
                                "upstream_unavailable"
                        )
                );

        service.generateInitial(sessionId, submittedAt);

        verify(jobStore, never()).uncertain(any(), any());
        verify(persistence, never()).fail(any(), any());
        verify(ragClient, never()).submitFrozenJob(anyString());
    }

    @Test
    void anotherWorkerWithActiveLeaseCannotSendOrRegenerate() {
        givenPreparedGeneratingFeedback();
        // Nested Mockito stubbing is invalid: construct the mock before when(...).
        AiRagJob existingJob = frozenJob(null);
        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(existingJob));
        // An active owner is holding the row-level DB-backed lease.
        when(dispatchLease.tryClaim(feedbackId)).thenReturn(null);

        service.generateInitial(sessionId, submittedAt);

        verifyNoInteractions(ragClient, initialBuilder, finalBuilder, jobStore);
        verify(dispatchLease, never()).release(any(), any());
        verify(persistence, never()).fail(any(), any());
    }

    @Test
    void conflictingFinalEventDoesNotReplaceOriginalRequest() {
        UUID originalSubmission = UUID.randomUUID();
        UUID duplicatedSubmission = UUID.randomUUID();

        AiRagJob existing = mock(AiRagJob.class);

        when(existing.getBeRequestId()).thenReturn(
                "feedback:final:" + sessionId + ":" + originalSubmission
        );

        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.of(existing));

        service.generateFinal(
                sessionId,
                duplicatedSubmission,
                submittedAt
        );

        verifyNoInteractions(
                finalBuilder,
                persistence,
                ragClient,
                jobStore,
                snapshotRepository,
                contractMapper
        );
    }
}
