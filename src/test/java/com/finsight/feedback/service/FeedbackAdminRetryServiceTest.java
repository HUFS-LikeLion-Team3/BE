package com.finsight.feedback.service;

import com.finsight.feedback.client.AiRagJobClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import com.finsight.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeedbackAdminRetryServiceTest {
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private AiFeedbackRepository feedbacks;
    private AiRagJobRepository jobs;
    private AiRagJobClient client;
    private AiFeedback feedback;
    private AiRagJob job;
    private FeedbackAdminRetryService service;

    @BeforeEach
    void setUp() {
        feedbacks = mock(AiFeedbackRepository.class);
        jobs = mock(AiRagJobRepository.class);
        client = mock(AiRagJobClient.class);
        feedback = mock(AiFeedback.class);
        job = mock(AiRagJob.class);
        when(feedbacks.findById(feedbackId)).thenReturn(Optional.of(feedback));
        when(feedbacks.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(feedback));
        when(jobs.findById(feedbackId)).thenReturn(Optional.of(job));
        when(jobs.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(job));
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.failed);
        when(job.getJobId()).thenReturn(jobId);
        when(job.getState()).thenReturn("accepted");
        service = new FeedbackAdminRetryService(feedbacks, jobs, client,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void retriesFailedFeedbackOnOriginalJobAndReopensIt() {
        when(client.retryFailedJob(jobId, adminId, "관측 한계 보완")).thenReturn(Map.of("status", "pending"));

        var result = service.retry(feedbackId, adminId, "관측 한계 보완");

        assertEquals("pending", result.aiStatus());
        verify(feedback).reopenFailedForRetry();
        verify(job).updateStatus("pending", null);
    }

    @Test
    void reopensWhenAiAlreadyAcceptedAnEarlierRetry() {
        when(client.retryFailedJob(any(), any(), any()))
                .thenThrow(new AiRagJobClient.AiRagHttpException(409, "user_retry_not_allowed"));
        when(client.getJobStatus(jobId)).thenReturn(Map.of("status", "generating"));

        service.retry(feedbackId, adminId, "재시도");

        verify(feedback).reopenFailedForRetry();
        verify(job).updateStatus("generating", null);
    }

    @Test
    void rejectsWhenAiRefusesAndJobIsStillFailed() {
        when(client.retryFailedJob(any(), any(), any()))
                .thenThrow(new AiRagJobClient.AiRagHttpException(409, "user_retry_not_allowed"));
        when(client.getJobStatus(jobId)).thenReturn(Map.of("status", "failed"));

        assertThrows(ApiException.class, () -> service.retry(feedbackId, adminId, "재시도"));
        verify(feedback, never()).reopenFailedForRetry();
    }

    @Test
    void rejectsNonFailedFeedbackUnacceptedJobAndBlankReason() {
        assertThrows(ApiException.class, () -> service.retry(feedbackId, adminId, " "));
        when(job.getState()).thenReturn("reconciliation_required");
        assertThrows(ApiException.class, () -> service.retry(feedbackId, adminId, "재시도"));
        when(job.getState()).thenReturn("accepted");
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.ready);
        assertThrows(ApiException.class, () -> service.retry(feedbackId, adminId, "재시도"));
        verifyNoInteractions(client);
    }
}
