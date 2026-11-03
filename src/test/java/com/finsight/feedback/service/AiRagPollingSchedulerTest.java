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
