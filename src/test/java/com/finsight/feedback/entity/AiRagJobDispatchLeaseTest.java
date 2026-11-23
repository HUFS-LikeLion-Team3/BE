package com.finsight.feedback.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiRagJobDispatchLeaseTest {
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final String packageId = "sha256:" + "a".repeat(64);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-06T09:00:00+09:00");

    private AiRagJob frozen() {
        return AiRagJob.create(feedbackId, sessionId, "initial", "request:" + feedbackId,
                packageId, "{\"frozen\":true}");
    }

    @Test
    void activeLeaseBlocksOtherWorkerButExpiredLeaseCanBeRecovered() {
        AiRagJob job = frozen();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(job.claimDispatch(first, now, Duration.ofMinutes(2)));
        assertEquals(first.toString(), job.getDispatchOwner());
        assertFalse(job.claimDispatch(second, now.plusSeconds(119), Duration.ofMinutes(2)));
        assertTrue(job.claimDispatch(second, now.plusSeconds(121), Duration.ofMinutes(2)));
        assertEquals(second.toString(), job.getDispatchOwner());
        // Late completion from the old worker must not clear the new owner's lease.
        job.releaseDispatch(first);
        assertEquals(second.toString(), job.getDispatchOwner());
        job.releaseDispatch(second);
        assertNull(job.getDispatchOwner());
        assertNull(job.getDispatchLeaseUntil());
    }

    @Test
    void acceptedOrReconciliationJobCannotBeClaimed() {
        AiRagJob accepted = frozen();
        UUID worker = UUID.randomUUID();
        assertTrue(accepted.claimDispatch(worker, now, Duration.ofMinutes(2)));
        accepted.accepted(UUID.randomUUID(), "received");
        assertNull(accepted.getDispatchOwner());
        assertFalse(accepted.claimDispatch(UUID.randomUUID(), now.plusMinutes(3),
                Duration.ofMinutes(2)));

        AiRagJob manual = frozen();
        assertTrue(manual.claimDispatch(worker, now, Duration.ofMinutes(2)));
        manual.uncertain("provider_completion_unknown");
        assertNull(manual.getDispatchOwner());
        assertFalse(manual.claimDispatch(UUID.randomUUID(), now.plusMinutes(3),
                Duration.ofMinutes(2)));
    }

    @Test
    void awaitingInitialAndMissingFrozenBodyCannotBeDispatched() {
        AiRagJob waiting = AiRagJob.create(feedbackId, sessionId, "final",
                "request:" + feedbackId, packageId, null);
        assertFalse(waiting.claimDispatch(UUID.randomUUID(), now, Duration.ofMinutes(2)));
    }
}
