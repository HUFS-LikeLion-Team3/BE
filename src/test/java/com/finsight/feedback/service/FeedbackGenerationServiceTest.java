package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * #25: 서비스 계층에서 작업 조회/접수 재시도/승인 결과 조회를 분리하는지 검증한다.
 * 외부 AI 서버나 DB에 연결하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackGenerationServiceTest {

    @Mock FeedbackGenerationInputBuilder initialBuilder;
    @Mock AiRagFinalInputBuilder finalBuilder;
    @Mock FeedbackGenerationPersistenceService persistence;
    @Mock AiFeedbackRepository feedbackRepository;
    @Mock AiFeedbackInputSnapshotRepository snapshotRepository;
    @Mock AiRagJobRepository jobRepository;
    @Mock AiRagJobStore jobStore;
    @Mock AiRagContractMapper contractMapper;
    @Mock AiRagJobClient ragClient;

    @InjectMocks FeedbackGenerationService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID aiJobId = UUID.randomUUID();
    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse("2026-10-27T10:00:00+09:00");
    private final String requestId = "feedback:initial:" + sessionId + ":v1";
    private final String requestJson = "{\"frozen\":true}";

    @BeforeEach
    void configureFeedbackProperties() {
        ReflectionTestUtils.setField(service, "promptVersion", "feedback-p0-v1");
        ReflectionTestUtils.setField(service, "policyVersion", "feedback-policy-p0-v1");
        ReflectionTestUtils.setField(service, "packageId", "sha256:" + "a".repeat(64));
    }

    private AiRagJob existingFrozenInitial() {
        AiRagJob job = mock(AiRagJob.class);
        when(job.getFeedbackId()).thenReturn(feedbackId);
        when(job.getRequestJson()).thenReturn(requestJson);
        when(job.getBeRequestId()).thenReturn(requestId);
        when(job.getJobId()).thenReturn(null);
        when(job.getStage()).thenReturn("initial");
        when(job.getSessionId()).thenReturn(sessionId);

        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.generating);
        when(persistence.prepare(
                eq(sessionId), eq(AiFeedback.FeedbackStage.INITIAL),
                eq(1), eq(requestId), any(), any(), eq(submittedAt)
        )).thenReturn(feedbackId);
        when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(feedback));
        when(jobRepository.findById(feedbackId)).thenReturn(Optional.of(job));
        when(jobRepository.findBySessionIdAndStage(sessionId, "final"))
                .thenReturn(Optional.empty());
        return job;
    }

    @Test
    void existingRequestIsLookedUpBeforePostingAgain() {
        existingFrozenInitial();
        when(ragClient.findByBeRequestId(requestId)).thenReturn(Map.of(
                "jobId", aiJobId.toString(), "status", "accepted"
        ));

        service.generateInitial(sessionId, submittedAt);

        verify(ragClient).findByBeRequestId(requestId);
        verify(ragClient, never()).submitFrozenJob(anyString());
        verify(jobStore).accepted(feedbackId, aiJobId, "accepted");
        verifyNoInteractions(initialBuilder);
    }

    @Test
    void frozenRequestIsPostedOnlyWhenRequestIdLookupReturns404() {
        existingFrozenInitial();
        when(ragClient.findByBeRequestId(requestId)).thenThrow(
                new AiRagJobClient.AiRagHttpException(404, "job_not_found")
        );
        when(ragClient.submitFrozenJob(requestJson)).thenReturn(Map.of(
                "jobId", aiJobId.toString(), "status", "received"
        ));

        service.generateInitial(sessionId, submittedAt);

        verify(ragClient).findByBeRequestId(requestId);
        verify(ragClient).submitFrozenJob(requestJson);
        verify(jobStore).accepted(feedbackId, aiJobId, "received");
        verifyNoInteractions(initialBuilder);
    }

    @Test
    void jobStatusUpdatesTrackingWithoutFetchingApprovedResult() {
        AiRagJob stored = mock(AiRagJob.class);
        when(stored.getJobId()).thenReturn(aiJobId);
        when(jobRepository.findById(feedbackId)).thenReturn(Optional.of(stored));
        Map<String, Object> status = Map.of(
                "status", "awaiting_initial",
                "ragSnapshotHash", "a".repeat(64)
        );
        when(ragClient.getJobStatus(aiJobId)).thenReturn(status);

        assertEquals(status, service.fetchJobStatus(feedbackId));
        verify(jobStore).updateStatus(feedbackId, "awaiting_initial", "a".repeat(64));
        verify(ragClient, never()).getApprovedResult(any());
    }

    @Test
    void approvedResultIsFetchedSeparatelyFromJobStatus() {
        AiRagJob stored = mock(AiRagJob.class);
        when(stored.getJobId()).thenReturn(aiJobId);
        when(jobRepository.findById(feedbackId)).thenReturn(Optional.of(stored));
        Map<String, Object> approved = Map.of(
                "stage", "initial", "flowSummary", "Approved feedback"
        );
        when(ragClient.getApprovedResult(aiJobId)).thenReturn(approved);

        assertEquals(approved, service.fetchApprovedResult(feedbackId));
        verify(ragClient, never()).getJobStatus(any());
        verifyNoInteractions(jobStore);
    }
}
