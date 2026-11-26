package com.finsight.feedback.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiFeedbackRetryTransitionTest {
    private AiFeedback feedback() {
        return AiFeedback.create(UUID.randomUUID(), AiFeedback.FeedbackStage.FINAL, 1, "feedback:final:x",
                "prompt", "policy", OffsetDateTime.now());
    }

    @Test
    void onlyFailedFeedbackReopensAndRetryIsCounted() {
        AiFeedback feedback = feedback();
        assertThrows(IllegalStateException.class, feedback::reopenFailedForRetry);
        feedback.markGenerating();
        feedback.markFailed("observation_limit_required");
        feedback.reopenFailedForRetry();
        assertEquals(AiFeedback.FeedbackStatus.generating, feedback.getStatus());
        assertNull(feedback.getFailureCode());
        assertEquals(1, feedback.getRetryCount());
    }
}
