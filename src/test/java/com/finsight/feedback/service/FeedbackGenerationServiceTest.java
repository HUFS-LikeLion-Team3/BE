
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackGenerationServiceTest {

    @Mock
    FeedbackGenerationInputBuilder initialBuilder;

    @Mock
    AiRagFinalInputBuilder finalBuilder;

    @Mock
    FeedbackGenerationPersistenceService persistence;

    @Mock
    AiFeedbackRepository feedbackRepository;

    @Mock
    AiFeedbackInputSnapshotRepository snapshotRepository;

    @Mock
    AiRagJobRepository jobRepository;

    @Mock
    AiRagJobStore jobStore;

    @Mock
    AiRagContractMapper contractMapper;

    @Mock
    AiRagJobClient ragClient;

    @Mock
    ApprovedFeedbackPersistenceService approvedPersistence;

    @InjectMocks
    FeedbackGenerationService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID aiJobId = UUID.randomUUID();

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse(
                    "2026-10-27T10:00:00+09:00"
            );

    private final String requestId =
            "feedback:initial:" + sessionId + ":v1";

    private final String requestJson =
            "{\"frozen\":true}";

    private final String packageId =
            "sha256:" + "a".repeat(64);

    @BeforeEach
    void configureFeedbackProperties() {
        ReflectionTestUtils.setField(
                service,
                "promptVersion",
                "feedback-p0-v1"
        );

        ReflectionTestUtils.setField(
                service,
                "policyVersion",
                "feedback-policy-p0-v1"
        );

        ReflectionTestUtils.setField(
                service,
                "packageId",
                packageId
        );
    }

    /**
     * Create a real frozen job instead of mocking its getters.
     *
     * This prevents unused getter stubs while preserving
     * the immutable request identity.
     */
    private AiRagJob existingFrozenInitial() {

        AiRagJob job = AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                requestId,
                packageId,
                requestJson
        );

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(
                        AiFeedback.FeedbackStatus.generating
                );

        when(persistence.prepare(
                eq(sessionId),
                eq(AiFeedback.FeedbackStage.INITIAL),
                eq(1),
                eq(requestId),
                any(),
                any(),
                eq(submittedAt)
        )).thenReturn(feedbackId);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(job));

        return job;
    }

    /**
     * Existing AI request:
     * look up by beRequestId and never resubmit.
     */
    @Test
    void existingRequestIsLookedUpBeforePostingAgain() {

        existingFrozenInitial();

        when(ragClient.findByBeRequestId(requestId))
                .thenReturn(Map.of(
                        "jobId", aiJobId.toString(),
                        "status", "accepted"
                ));

        service.generateInitial(
                sessionId,
                submittedAt
        );

        verify(ragClient, times(1))
                .findByBeRequestId(requestId);

        verify(ragClient, never())
                .submitFrozenJob(anyString());

        verify(jobStore).accepted(
                feedbackId,
                aiJobId,
                "accepted"
        );

        verifyNoInteractions(
                initialBuilder,
                snapshotRepository,
                contractMapper
        );
    }

    /**
     * Only HTTP 404 permits reposting the exact
     * original frozen request JSON.
     */
    @Test
    void frozenRequestIsPostedOnlyWhenRequestIdLookupReturns404() {

        existingFrozenInitial();

        when(ragClient.findByBeRequestId(requestId))
                .thenThrow(
                        new AiRagJobClient.AiRagHttpException(
                                404,
                                "job_not_found"
                        )
                );

        when(ragClient.submitFrozenJob(requestJson))
                .thenReturn(Map.of(
                        "jobId", aiJobId.toString(),
                        "status", "received"
                ));

        service.generateInitial(
                sessionId,
                submittedAt
        );

        verify(ragClient, times(1))
                .findByBeRequestId(requestId);

        verify(ragClient, times(1))
                .submitFrozenJob(requestJson);

        verify(jobStore).accepted(
                feedbackId,
                aiJobId,
                "received"
        );

        verifyNoInteractions(
                initialBuilder,
                snapshotRepository,
                contractMapper
        );
    }

    /**
     * Status polling must never fetch
     * the approved feedback body.
     */
    @Test
    void jobStatusUpdatesTrackingWithoutFetchingApprovedResult() {

        AiRagJob stored = mock(AiRagJob.class);

        when(stored.getJobId())
                .thenReturn(aiJobId);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(stored));

        Map<String, Object> status = Map.of(
                "status", "awaiting_initial",
                "ragSnapshotHash", "a".repeat(64)
        );

        when(ragClient.getJobStatus(aiJobId))
                .thenReturn(status);

        assertEquals(
                status,
                service.fetchJobStatus(feedbackId)
        );

        verify(jobStore).updateStatus(
                feedbackId,
                "awaiting_initial",
                "a".repeat(64)
        );

        verify(ragClient, never())
                .getApprovedResult(any());

        verify(ragClient, never())
                .getApprovedResultRaw(any());
    }

    /**
     * Issue #26:
     * Fetch the exact original approved JSON,
     * parse it and capture it before downstream persistence.
     */
    @Test
    void approvedResultIsFetchedSeparatelyFromJobStatus() {

        AiRagJob stored = mock(AiRagJob.class);

        when(stored.getJobId())
                .thenReturn(aiJobId);

        when(jobRepository.findById(feedbackId))
                .thenReturn(Optional.of(stored));

        String resultHash = "b".repeat(64);

        String rawJson =
                "{\"stage\":\"initial\","
                        + "\"flowSummary\":\"Approved feedback\","
                        + "\"resultHash\":\""
                        + resultHash
                        + "\"}";

        Map<String, Object> approved = Map.of(
                "stage", "initial",
                "flowSummary", "Approved feedback",
                "resultHash", resultHash
        );

        when(ragClient.getApprovedResultRaw(aiJobId))
                .thenReturn(rawJson);

        when(ragClient.parseResponse(rawJson))
                .thenReturn(approved);

        Map<String, Object> result =
                service.fetchApprovedResult(feedbackId);

        assertEquals(approved, result);

        verify(ragClient, times(1))
                .getApprovedResultRaw(aiJobId);

        verify(ragClient)
                .parseResponse(rawJson);

        verify(jobStore).captureApprovedResult(
                feedbackId,
                aiJobId,
                rawJson,
                resultHash
        );

        verify(ragClient, never())
                .getJobStatus(any());

        verify(ragClient, never())
                .getApprovedResult(any());
    }
}
