package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiRagDeliveryCoordinatorTest {
    @Mock AiRagJobRepository jobs;
    @Mock AiFeedbackRepository feedbacks;
    @Mock AiRagJobClient client;
    @Mock AiRagJobStore store;
    @Mock FeedbackGenerationService generation;
    @Mock ApprovedFeedbackPersistenceService persistence;
    @Mock FeedbackGenerationPersistenceService failureRecorder;
    @InjectMocks AiRagDeliveryCoordinator coordinator;

    private final UUID feedbackId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID jobId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID sessionId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final String hash = "a".repeat(64);

    private AiRagJob job(String state, boolean captured) {
        AiRagJob j = mock(AiRagJob.class);
        lenient().when(j.getFeedbackId()).thenReturn(feedbackId);
        lenient().when(j.getJobId()).thenReturn(jobId);
        lenient().when(j.getState()).thenReturn(state);
        lenient().when(j.getApprovedResultJson()).thenReturn(captured ? "{\"approved\":true}" : null);
        lenient().when(j.getResultHash()).thenReturn(captured ? hash : null);
        lenient().when(j.getSessionId()).thenReturn(sessionId);
        lenient().when(j.getStage()).thenReturn("initial");
        lenient().when(j.getBeRequestId()).thenReturn("feedback:initial:" + sessionId + ":v1");
        lenient().when(j.getPackageId()).thenReturn("sha256:" + "b".repeat(64));
        lenient().when(j.getRequestJson()).thenReturn("{\"beInputHash\":\"" + "c".repeat(64) + "\"}");
        return j;
    }

    // Build the nested mock completely before starting repository stubbing.
    // Otherwise job() calls Mockito.when() while an outer when() is unfinished.
    private void stubJob(String state, boolean captured) {
        AiRagJob saved = job(state, captured);
        when(jobs.findById(feedbackId)).thenReturn(Optional.of(saved));
    }

    private void feedback(AiFeedback.FeedbackStatus value) {
        AiFeedback f = mock(AiFeedback.class);
        lenient().when(f.getStatus()).thenReturn(value);
        when(feedbacks.findById(feedbackId)).thenReturn(Optional.of(f));
    }

    private Map<String, Object> status(String name) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", jobId.toString());
        value.put("feedbackId", feedbackId.toString());
        value.put("sessionId", sessionId.toString());
        value.put("stage", "initial");
        value.put("beRequestId", "feedback:initial:" + sessionId + ":v1");
        value.put("packageId", "sha256:" + "b".repeat(64));
        value.put("beInputHash", "c".repeat(64));
        value.put("status", name);
        return value;
    }

    @Test
    void readyJobCapturesThenStoresBeforePostingStoredDelivery() {
        AiRagJob pending = job("accepted", false);
        AiRagJob captured = job("accepted", true);
        when(jobs.findById(feedbackId)).thenReturn(Optional.of(pending), Optional.of(captured));
        feedback(AiFeedback.FeedbackStatus.generating);
        when(client.getJobStatus(jobId)).thenReturn(status("ready"));

        assertEquals(AiRagDeliveryCoordinator.Outcome.STORED, coordinator.processOne(feedbackId));
        var ordered = inOrder(generation, persistence, client, store);
        ordered.verify(client).getJobStatus(jobId);
        ordered.verify(generation).fetchApprovedResult(feedbackId);
        ordered.verify(persistence).persistCaptured(feedbackId);
        ordered.verify(client).reportDelivery(eq(jobId), eq("stored"), eq(hash), startsWith("be-issue26:stored:"));
        ordered.verify(store).stored(feedbackId, jobId, hash);
    }

    @Test
    void awaitingReviewNeverReadsApprovedResultOrWritesFeedback() {
        stubJob("accepted", false);
        feedback(AiFeedback.FeedbackStatus.generating);
        when(client.getJobStatus(jobId)).thenReturn(status("awaiting_review"));

        assertEquals(AiRagDeliveryCoordinator.Outcome.REVIEW, coordinator.processOne(feedbackId));
        verifyNoInteractions(generation, persistence, failureRecorder);
        verify(client, never()).reportDelivery(any(), any(), any(), any());
    }

    @Test
    void blockedOriginalIsRetriedWithoutFetchingAiResultAgain() {
        stubJob("blocked", true);
        feedback(AiFeedback.FeedbackStatus.generating);
        when(persistence.persistCaptured(feedbackId))
                .thenThrow(new IllegalStateException("approved_source_document_missing"));

        assertEquals(AiRagDeliveryCoordinator.Outcome.BLOCKED, coordinator.processOne(feedbackId));
        verifyNoInteractions(generation);
        verify(client, never()).getJobStatus(any());
        var ordered = inOrder(store, client);
        ordered.verify(store).blocked(feedbackId, "approved_source_document_missing");
        ordered.verify(client).reportDelivery(eq(jobId), eq("blocked"), eq(hash), contains("approved_source_document_missing"));
    }

    @Test
    void blockedResultIsStoredAfterSourceRepairWithoutRefetchOrRegeneration() {
        // The approved result and its hash were captured before storage was blocked.
        // The operator fixes the missing source between these two delivery attempts.
        stubJob("blocked", true);
        feedback(AiFeedback.FeedbackStatus.generating);
        when(persistence.persistCaptured(feedbackId))
                .thenThrow(new IllegalStateException("approved_source_document_missing"))
                .thenReturn(true);

        assertEquals(AiRagDeliveryCoordinator.Outcome.BLOCKED, coordinator.processOne(feedbackId));
        assertEquals(AiRagDeliveryCoordinator.Outcome.STORED, coordinator.processOne(feedbackId));

        var ordered = inOrder(persistence, store, client);
        ordered.verify(persistence).persistCaptured(feedbackId);
        ordered.verify(store).blocked(feedbackId, "approved_source_document_missing");
        ordered.verify(client).reportDelivery(eq(jobId), eq("blocked"), eq(hash),
                contains("approved_source_document_missing"));
        ordered.verify(persistence).persistCaptured(feedbackId);
        ordered.verify(client).reportDelivery(eq(jobId), eq("stored"), eq(hash),
                startsWith("be-issue26:stored:"));
        ordered.verify(store).stored(feedbackId, jobId, hash);
        verifyNoInteractions(generation);
        verify(client, never()).getJobStatus(any());
        verify(client, never()).getApprovedResultRaw(any());
        verify(client, never()).submitFrozenJob(any());
    }

    @Test
    void awaitingStorageWithoutSavedOriginalRequiresManualReconciliation() {
        stubJob("accepted", false);
        feedback(AiFeedback.FeedbackStatus.generating);
        when(client.getJobStatus(jobId)).thenReturn(status("awaiting_storage"));

        assertEquals(AiRagDeliveryCoordinator.Outcome.RECONCILIATION, coordinator.processOne(feedbackId));
        verify(store).uncertain(feedbackId, "approved_result_missing_after_block");
        verifyNoInteractions(generation, persistence);
        verify(client, never()).getApprovedResultRaw(any());
    }

    @Test
    void readyInDbButDeliveryLostOnlyPostsStoredWithoutRewriting() {
        stubJob("accepted", true);
        feedback(AiFeedback.FeedbackStatus.ready);
        assertEquals(AiRagDeliveryCoordinator.Outcome.STORED, coordinator.processOne(feedbackId));
        verify(client).reportDelivery(eq(jobId), eq("stored"), eq(hash), anyString());
        verify(store).stored(feedbackId, jobId, hash);
        verifyNoInteractions(generation, persistence);
        verify(client, never()).getJobStatus(any());
    }

    @Test
    void statusWithDifferentIdentityNeverChangesDatabase() {
        stubJob("accepted", false);
        feedback(AiFeedback.FeedbackStatus.generating);
        Map<String, Object> forged = status("ready");
        forged.put("beInputHash", "tampered");
        when(client.getJobStatus(jobId)).thenReturn(forged);

        assertEquals(AiRagDeliveryCoordinator.Outcome.RECONCILIATION,
                coordinator.processOne(feedbackId));
        verify(store).uncertain(feedbackId, "ai_job_status_identity_mismatch");
        verify(store, never()).updateStatus(any(), any(), any());
        verifyNoInteractions(generation, persistence);
    }

    @Test
    void failedAiJobIsMarkedFailedWithoutAutomaticRetry() {
        stubJob("accepted", false);
        feedback(AiFeedback.FeedbackStatus.generating);
        Map<String, Object> failed = status("failed");
        failed.put("failureCode", "provider_error");
        when(client.getJobStatus(jobId)).thenReturn(failed);

        assertEquals(AiRagDeliveryCoordinator.Outcome.FAILED, coordinator.processOne(feedbackId));
        verify(failureRecorder).fail(feedbackId, "provider_error");
        verifyNoInteractions(generation, persistence);
        verify(client, never()).submitFrozenJob(any());
    }

    @Test
    void lostStoredDeliveryResponseIsRetriedWithoutFetchingOrRewritingReadyFeedback() {
        // BE has committed the ready feedback and captured approved result already.
        // The first AI delivery POST may succeed remotely while its HTTP response is lost.
        stubJob("accepted", true);
        feedback(AiFeedback.FeedbackStatus.ready);
        when(client.reportDelivery(eq(jobId), eq("stored"), eq(hash), startsWith("be-issue26:stored:")))
                .thenThrow(new IllegalStateException("simulated_lost_delivery_response"))
                .thenReturn(Map.of("status", "stored"));

        assertThrows(IllegalStateException.class, () -> coordinator.processOne(feedbackId));
        assertEquals(AiRagDeliveryCoordinator.Outcome.STORED, coordinator.processOne(feedbackId));

        verify(client, times(2)).reportDelivery(eq(jobId), eq("stored"), eq(hash),
                eq("be-issue26:stored:" + hash));
        verify(store, times(1)).stored(feedbackId, jobId, hash);
        verifyNoInteractions(generation, persistence, failureRecorder);
        verify(client, never()).getJobStatus(any());
        verify(client, never()).getApprovedResultRaw(any());
        verify(client, never()).submitFrozenJob(any());
    }

    @Test
    void alreadyStoredIsIdempotent() {
        stubJob("stored", true);
        feedback(AiFeedback.FeedbackStatus.ready);
        assertEquals(AiRagDeliveryCoordinator.Outcome.STORED, coordinator.processOne(feedbackId));
        verifyNoInteractions(client, store, generation, persistence);
    }
}
