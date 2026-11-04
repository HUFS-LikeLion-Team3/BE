package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiRagJob;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiRagDeliveryStateTest {
    @Test
    void storedCanNeverBeDowngradedByLateBlockedNotification() {
        UUID feedback = UUID.randomUUID();
        UUID aiJob = UUID.randomUUID();
        String hash = "a".repeat(64);
        AiRagJob row = AiRagJob.create(feedback, UUID.randomUUID(), "initial",
                "feedback:initial:test:v1", "sha256:" + "b".repeat(64), "{}");
        row.accepted(aiJob, "ready");
        row.captureApprovedResult(aiJob, "{\"output\":{}}", hash);
        row.markDeliveryBlocked("approved_source_document_missing");
        assertEquals("blocked", row.getState());
        row.markDeliveryStored(aiJob, hash);
        assertEquals("stored", row.getState());
        row.markDeliveryBlocked("late_failed_delivery");
        assertEquals("stored", row.getState());
        assertNull(row.getFailureCode());
    }

    @Test
    void storedRequiresImmutableOriginalAndExactHash() {
        UUID aiJob = UUID.randomUUID();
        AiRagJob row = AiRagJob.create(UUID.randomUUID(), UUID.randomUUID(),
                "initial", "feedback:test", "sha256:" + "a".repeat(64), "{}");
        row.accepted(aiJob, "ready");
        assertThrows(IllegalStateException.class,
                () -> row.markDeliveryStored(aiJob, "b".repeat(64)));
        row.captureApprovedResult(aiJob, "{\"output\":{}}", "a".repeat(64));
        assertThrows(IllegalStateException.class,
                () -> row.markDeliveryStored(aiJob, "b".repeat(64)));
    }
}
