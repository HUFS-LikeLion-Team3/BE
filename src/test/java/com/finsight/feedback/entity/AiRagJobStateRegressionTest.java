
package com.finsight.feedback.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobStateRegressionTest {

    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID aiJobId = UUID.randomUUID();

    private final String packageId =
            "sha256:" + "a".repeat(64);

    private final String requestId =
            "feedback:initial:" + sessionId + ":v1";

    private final String requestJson =
            "{\"frozen\":true}";

    private final String resultJson =
            "{\"approved\":true}";

    private final String resultHash =
            "b".repeat(64);

    private AiRagJob frozen() {
        return AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                requestId,
                packageId,
                requestJson
        );
    }

    private AiRagJob stored() {
        AiRagJob job = frozen();

        job.accepted(aiJobId, "received");

        job.updateStatus(
                "ready",
                "c".repeat(64)
        );

        job.captureApprovedResult(
                aiJobId,
                resultJson,
                resultHash
        );

        job.markDeliveryStored(
                aiJobId,
                resultHash
        );

        return job;
    }

    @Test
    void lateFailureDoesNotRegressStoredJob() {
        AiRagJob job = stored();

        job.uncertain("late_provider_failure");

        assertEquals("stored", job.getState());
        assertNull(job.getFailureCode());
        assertEquals(aiJobId, job.getJobId());
        assertEquals(resultHash, job.getResultHash());
        assertEquals(resultJson, job.getApprovedResultJson());
    }

    @Test
    void lateAcceptedReceiptDoesNotRegressStoredJob() {
        AiRagJob job = stored();

        job.accepted(aiJobId, "received");

        assertEquals("stored", job.getState());
        assertEquals("ready", job.getAiStatus());
        assertEquals(resultHash, job.getResultHash());
    }

    @Test
    void latePollingDoesNotChangeStoredMetadata() {
        AiRagJob job = stored();

        job.updateStatus(
                "failed",
                "d".repeat(64)
        );

        assertEquals("stored", job.getState());
        assertEquals("ready", job.getAiStatus());
        assertEquals(
                "c".repeat(64),
                job.getRagSnapshotHash()
        );
    }

    @Test
    void duplicateAcceptedReceiptPreservesNewerStatus() {
        AiRagJob job = frozen();

        job.accepted(aiJobId, "received");

        job.updateStatus(
                "awaiting_review",
                "c".repeat(64)
        );

        job.accepted(aiJobId, "received");

        assertEquals("accepted", job.getState());
        assertEquals(
                "awaiting_review",
                job.getAiStatus()
        );
        assertEquals(
                "c".repeat(64),
                job.getRagSnapshotHash()
        );
    }

    @Test
    void differentSnapshotHashIsRejected() {
        AiRagJob job = frozen();

        job.accepted(aiJobId, "received");

        job.updateStatus(
                "generating",
                "c".repeat(64)
        );

        assertThrows(
                IllegalStateException.class,
                () -> job.updateStatus(
                        "ready",
                        "d".repeat(64)
                )
        );

        assertEquals(
                "c".repeat(64),
                job.getRagSnapshotHash()
        );

        assertEquals(
                "generating",
                job.getAiStatus()
        );
    }

    @Test
    void manualReconciliationCannotBeAutomaticallyRestarted() {
        AiRagJob job = AiRagJob.create(
                feedbackId,
                sessionId,
                "final",
                "feedback:final:" + sessionId
                        + ":" + UUID.randomUUID(),
                packageId,
                null
        );

        job.uncertain(
                "original_frozen_input_missing"
        );

        assertThrows(
                IllegalStateException.class,
                () -> job.freeze(requestJson)
        );

        assertThrows(
                IllegalStateException.class,
                () -> job.accepted(
                        aiJobId,
                        "received"
                )
        );

        assertEquals(
                "reconciliation_required",
                job.getState()
        );

        assertEquals(
                "original_frozen_input_missing",
                job.getFailureCode()
        );

        assertNull(job.getJobId());
        assertNull(job.getRequestJson());
    }

    @Test
    void blockedJobPreservesCapturedResult() {
        AiRagJob job = frozen();

        job.accepted(aiJobId, "received");

        job.captureApprovedResult(
                aiJobId,
                resultJson,
                resultHash
        );

        job.markDeliveryBlocked(
                "approved_storage_blocked"
        );

        job.uncertain("late_provider_failure");

        job.updateStatus(
                "failed",
                "d".repeat(64)
        );

        job.accepted(aiJobId, "received");

        assertEquals("blocked", job.getState());

        assertEquals(
                "approved_storage_blocked",
                job.getFailureCode()
        );

        assertEquals(
                resultJson,
                job.getApprovedResultJson()
        );

        assertEquals(
                resultHash,
                job.getResultHash()
        );
    }
}

