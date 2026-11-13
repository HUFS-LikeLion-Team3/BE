package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

class AiRagPollingSchedulerTest {
    @Test
    void scansPersistedJobsAndProcessesIndividualStatuses() {
        AiRagJobRepository repo = mock(AiRagJobRepository.class);
        AiRagDeliveryCoordinator coordinator = mock(AiRagDeliveryCoordinator.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repo.findDeliveryCandidates(List.of(
                AiFeedback.FeedbackStatus.generating, AiFeedback.FeedbackStatus.ready)))
                .thenReturn(List.of(first, second));
        when(coordinator.processOne(first)).thenReturn(AiRagDeliveryCoordinator.Outcome.WAITING);
        when(coordinator.processOne(second)).thenReturn(AiRagDeliveryCoordinator.Outcome.STORED);

        new AiRagPollingScheduler(repo, coordinator).poll();
        verify(coordinator).processOne(first);
        verify(coordinator).processOne(second);
    }

    @Test
    void freshSchedulerAfterRestartRescansReadyJobWhenDeliveryResponseWasLost() {
        // The old scheduler's in-memory retry delay does not survive process restart.
        // A new instance must scan durable database candidates again.
        AiRagJobRepository repo = mock(AiRagJobRepository.class);
        AiRagDeliveryCoordinator coordinator = mock(AiRagDeliveryCoordinator.class);
        UUID feedbackId = UUID.randomUUID();
        when(repo.findDeliveryCandidates(List.of(
                AiFeedback.FeedbackStatus.generating, AiFeedback.FeedbackStatus.ready)))
                .thenReturn(List.of(feedbackId));
        when(coordinator.processOne(feedbackId))
                .thenThrow(new IllegalStateException("simulated_lost_delivery_response"))
                .thenReturn(AiRagDeliveryCoordinator.Outcome.STORED);

        new AiRagPollingScheduler(repo, coordinator).poll(); // simulated old process
        new AiRagPollingScheduler(repo, coordinator).poll(); // simulated new process

        verify(repo, times(2)).findDeliveryCandidates(List.of(
                AiFeedback.FeedbackStatus.generating, AiFeedback.FeedbackStatus.ready));
        verify(coordinator, times(2)).processOne(feedbackId);
    }

    @Test
    void oneJobFailingCannotPreventAnotherFromProcessing() {
        AiRagJobRepository repo = mock(AiRagJobRepository.class);
        AiRagDeliveryCoordinator coordinator = mock(AiRagDeliveryCoordinator.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repo.findDeliveryCandidates(anyList())).thenReturn(List.of(first, second));
        when(coordinator.processOne(first)).thenThrow(new IllegalStateException("test"));
        when(coordinator.processOne(second)).thenReturn(AiRagDeliveryCoordinator.Outcome.WAITING);

        new AiRagPollingScheduler(repo, coordinator).poll();
        verify(coordinator).processOne(second);
    }
}
