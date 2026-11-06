package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #27 FINAL dependency regression tests. No live AI or database is used.
 * The submitted FINAL snapshot must survive while INITIAL has no job ID,
 * and must be reused unchanged when INITIAL is eventually accepted.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackFinalAwaitingInitialRecoveryTest {

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
    private final UUID initialFeedbackId = UUID.randomUUID();
    private final UUID finalFeedbackId = UUID.randomUUID();
    private final UUID initialAiJobId = UUID.randomUUID();
    private final UUID finalAiJobId = UUID.randomUUID();
    private final String packageId = "sha256:" + "a".repeat(64);
    private final String finalRequestId = "feedback:final:" + sessionId + ":" + UUID.randomUUID();
    private final OffsetDateTime cutoff = OffsetDateTime.parse("2026-11-06T09:00:00+09:00");

    private AiRagJob awaitingFinal() {
        return AiRagJob.create(
                finalFeedbackId, sessionId, "final", finalRequestId, packageId, null
        );
    }

    private AiRagJob initialWithoutReceipt() {
        return AiRagJob.create(
                initialFeedbackId, sessionId, "initial",
                "feedback:initial:" + sessionId + ":v1", packageId, "{\"initial\":true}"
        );
    }

    @Test
    void finalRemainsFrozenAndUnsentUntilInitialJobIdExists() {
        AiRagJob waiting = awaitingFinal();
        AiRagJob initial = initialWithoutReceipt();

        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.of(waiting));
        when(jobRepository.findBySessionIdAndStage(sessionId, "initial"))
                .thenReturn(Optional.of(initial));

        service.resumeFinal(sessionId);

        assertEquals("awaiting_initial_job", waiting.getState());
        assertNull(waiting.getRequestJson());
        assertNull(waiting.getJobId());
        verifyNoInteractions(
                snapshotRepository, feedbackRepository, contractMapper,
                jobStore, dispatchLease, ragClient, initialBuilder, finalBuilder
        );
    }

    @Test
    void acceptedInitialAllowsExactlyTheStoredFinalSnapshotToBeDispatched() {
        AiRagJob initial = initialWithoutReceipt();
        initial.accepted(initialAiJobId, "received");
        AiRagJob waiting = awaitingFinal();

        Map<String, Object> originalRequest = Map.of(
                "inputCutoffAt", cutoff.minusDays(3).toString(),
                "outcomeCutoffAt", cutoff.toString(),
                "submittedMemo", "original-memo"
        );
        Map<String, Object> originalPredictions = Map.of("frozen", "predictions");
        String inputHash = "b".repeat(64);
        AiFeedbackInputSnapshot savedSnapshot = AiFeedbackInputSnapshot.create(
                finalFeedbackId, originalRequest, originalPredictions,
                Map.of("frozen", "baseline"), Map.of("frozen", "result"),
                Map.of("frozen", "reflection"), inputHash
        );
        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getId()).thenReturn(finalFeedbackId);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.generating);

        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.of(waiting));
        when(jobRepository.findBySessionIdAndStage(sessionId, "initial"))
                .thenReturn(Optional.of(initial));
        when(feedbackRepository.findById(finalFeedbackId))
                .thenReturn(Optional.of(feedback));
        when(snapshotRepository.findByAiFeedbackId(finalFeedbackId))
                .thenReturn(Optional.of(savedSnapshot));

        String frozenFinalJson = "{\"frozenFinal\":true}";
        when(contractMapper.envelope(
                eq(feedback), any(FeedbackGenerationInput.class),
                eq(packageId), eq(initialAiJobId)
        )).thenReturn(frozenFinalJson);
        when(jobStore.ensure(
                eq(finalFeedbackId), eq(sessionId), eq("final"),
                eq(finalRequestId), eq(packageId), eq(frozenFinalJson)
        )).thenAnswer(invocation -> {
            waiting.freeze(frozenFinalJson);
            return waiting;
        });
        when(jobRepository.findById(finalFeedbackId))
                .thenReturn(Optional.of(waiting));
        UUID leaseOwner = UUID.randomUUID();
        when(dispatchLease.tryClaim(finalFeedbackId)).thenReturn(leaseOwner);
        when(ragClient.findByBeRequestId(finalRequestId))
                .thenThrow(new AiRagJobClient.AiRagHttpException(404, "not_found"));
        when(ragClient.submitFrozenJob(frozenFinalJson)).thenReturn(Map.of(
                "jobId", finalAiJobId.toString(),
                "beRequestId", finalRequestId,
                "feedbackId", finalFeedbackId.toString(),
                "status", "received"
        ));

        service.resumeFinal(sessionId);

        ArgumentCaptor<FeedbackGenerationInput> frozenInput =
                ArgumentCaptor.forClass(FeedbackGenerationInput.class);
        verify(contractMapper).envelope(
                eq(feedback), frozenInput.capture(), eq(packageId), eq(initialAiJobId)
        );
        assertEquals(originalRequest, frozenInput.getValue().requestSnapshot());
        assertEquals(originalPredictions, frozenInput.getValue().predictionSnapshot());
        assertEquals(inputHash, frozenInput.getValue().inputHash());
        assertEquals(cutoff, frozenInput.getValue().inputCutoffAt());
        assertEquals("frozen", waiting.getState());
        assertEquals(frozenFinalJson, waiting.getRequestJson());

        verify(jobStore).ensure(
                finalFeedbackId, sessionId, "final", finalRequestId, packageId, frozenFinalJson
        );
        verify(ragClient).findByBeRequestId(finalRequestId);
        verify(ragClient).submitFrozenJob(frozenFinalJson);
        verify(jobStore).accepted(finalFeedbackId, finalAiJobId, "received");
        verify(dispatchLease).release(finalFeedbackId, leaseOwner);
        verifyNoInteractions(initialBuilder, finalBuilder, persistence);
    }

    @Test
    void alreadyAcceptedFinalIsNeverSubmittedAgain() {
        AiRagJob accepted = AiRagJob.create(
                finalFeedbackId, sessionId, "final", finalRequestId,
                packageId, "{\"frozenFinal\":true}"
        );
        accepted.accepted(finalAiJobId, "received");
        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.of(accepted));

        service.resumeFinal(sessionId);

        verifyNoInteractions(
                snapshotRepository, feedbackRepository, contractMapper,
                jobStore, dispatchLease, ragClient, initialBuilder, finalBuilder
        );
        assertEquals(finalAiJobId, accepted.getJobId());
        assertEquals("accepted", accepted.getState());
    }

    @Test
    void reconciliationRequiredFinalIsNotAutomaticallyResent() {
        AiRagJob uncertain = awaitingFinal();
        uncertain.uncertain("provider_completion_unknown");
        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.of(uncertain));

        service.resumeFinal(sessionId);

        assertEquals("reconciliation_required", uncertain.getState());
        verifyNoInteractions(
                snapshotRepository, feedbackRepository, contractMapper,
                jobStore, dispatchLease, ragClient, initialBuilder, finalBuilder
        );
    }
}
