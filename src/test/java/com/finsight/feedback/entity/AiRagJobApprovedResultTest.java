package com.finsight.feedback.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobApprovedResultTest {
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private AiRagJob accepted() {
        AiRagJob job = AiRagJob.create(
                feedbackId, sessionId, "initial", "be-request-1",
                "sha256:package", "{\"input\":true}");
        job.accepted(jobId, "approved");
        return job;
    }

    @Test
    void rawResponseAndHashAreImmutableAndRetrievable() {
        AiRagJob job = accepted();
        String raw = " {\n  \"resultHash\": \"sha256:abcd\", \"text\": \"  A  \"\n}";
        job.captureApprovedResult(jobId, raw, "sha256:abcd");
        var firstTime = job.getApprovedResultCapturedAt();
        job.captureApprovedResult(jobId, raw, "sha256:abcd");
        assertEquals(raw, job.getApprovedResultJson());
        assertEquals("sha256:abcd", job.getResultHash());
        assertEquals(firstTime, job.getApprovedResultCapturedAt());
        assertThrows(IllegalStateException.class,
                () -> job.captureApprovedResult(jobId, "different", "sha256:abcd"));
        assertThrows(IllegalStateException.class,
                () -> job.captureApprovedResult(jobId, raw, "sha256:other"));
    }

    @Test
    void invalidJsonMayBeCapturedUnmodifiedForDiagnosis() {
        AiRagJob job = accepted();
        job.captureApprovedResult(jobId, "{invalid", null);
        assertEquals("{invalid", job.getApprovedResultJson());
        assertNull(job.getResultHash());
        assertNotNull(job.getApprovedResultCapturedAt());
    }

    @Test
    void wrongJobCannotOverwriteStoredResult() {
        AiRagJob job = accepted();
        assertThrows(IllegalStateException.class,
                () -> job.captureApprovedResult(UUID.randomUUID(), "{}", "hash"));
        assertNull(job.getApprovedResultJson());
    }
}
