package com.finsight.feedback.service;

import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * #27: verify the scheduler's pacing without sleeping or calling the AI server.
 * In-memory due times are deliberately moved into the past between attempts.
 */
class AiRagPollingTimingTest {

    private final AiRagJobRepository repository = mock(AiRagJobRepository.class);
    private final AiRagDeliveryCoordinator coordinator = mock(AiRagDeliveryCoordinator.class);
    private final AiRagPollingScheduler scheduler = new AiRagPollingScheduler(repository, coordinator);
    private final UUID feedbackId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private Map<UUID, Long> dueAt() {
        return (Map<UUID, Long>) ReflectionTestUtils.getField(scheduler, "dueAt");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> failures() {
        return (Map<UUID, Integer>) ReflectionTestUtils.getField(scheduler, "failures");
    }

    private void selectOneCandidate() {
        when(repository.findDeliveryCandidates(anyList())).thenReturn(List.of(feedbackId));
    }

    private void assertNextDelay(long expectedMillis) {
        long before = System.currentTimeMillis();
        scheduler.poll();
        long after = System.currentTimeMillis();
        Long scheduled = dueAt().get(feedbackId);
        assertNotNull(scheduled, "poll must schedule the next attempt");
        assertTrue(scheduled >= before + expectedMillis,
                () -> "scheduled too early: " + scheduled + ", expected at least " + (before + expectedMillis));
        assertTrue(scheduled <= after + expectedMillis,
                () -> "scheduled too late: " + scheduled + ", expected at most " + (after + expectedMillis));
    }

    @Test
    void waitingUsesFiveSecondsAndDoesNotPollBeforeDueTime() {
        selectOneCandidate();
        when(coordinator.processOne(feedbackId)).thenReturn(AiRagDeliveryCoordinator.Outcome.WAITING);

        assertNextDelay(5_000L);
        scheduler.poll();
        verify(coordinator, times(1)).processOne(feedbackId);

        dueAt().put(feedbackId, 0L);
        assertNextDelay(5_000L);
        verify(coordinator, times(2)).processOne(feedbackId);
    }

    @Test
    void reviewAndBlockedUseThirtySeconds() {
        selectOneCandidate();
        when(coordinator.processOne(feedbackId)).thenReturn(
                AiRagDeliveryCoordinator.Outcome.REVIEW,
                AiRagDeliveryCoordinator.Outcome.BLOCKED);

        assertNextDelay(30_000L);
        dueAt().put(feedbackId, 0L);
        assertNextDelay(30_000L);
        verify(coordinator, times(2)).processOne(feedbackId);
    }

    @Test
    void transportFailuresBackOffFiveTenTwentyFortyThenCapAtSixtySeconds() {
        selectOneCandidate();
        AtomicInteger attempts = new AtomicInteger();
        when(coordinator.processOne(feedbackId)).thenAnswer(call -> {
            if (attempts.getAndIncrement() < 6) {
                throw new IllegalStateException("simulated_transport_failure");
            }
            return AiRagDeliveryCoordinator.Outcome.WAITING;
        });

        for (long delay : new long[]{5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L}) {
            dueAt().put(feedbackId, 0L);
            assertNextDelay(delay);
        }
        assertEquals(5, failures().get(feedbackId));
        dueAt().put(feedbackId, 0L);
        assertNextDelay(5_000L);
        assertFalse(failures().containsKey(feedbackId), "a successful poll resets the failure counter");
        verify(coordinator, times(7)).processOne(feedbackId);
    }

    @Test
    void reconciliationOutcomeIsPacedWithoutImmediateRepeat() {
        selectOneCandidate();
        when(coordinator.processOne(feedbackId)).thenReturn(AiRagDeliveryCoordinator.Outcome.RECONCILIATION);

        assertNextDelay(60_000L);
        scheduler.poll();
        verify(coordinator, times(1)).processOne(feedbackId);
    }
}
