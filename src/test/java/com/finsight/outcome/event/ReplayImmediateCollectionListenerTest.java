package com.finsight.outcome.event;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.service.DailyOutcomeScheduler;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayImmediateCollectionListenerTest {
    @Mock LearningSessionRepository sessions;
    @Mock DailyOutcomeScheduler scheduler;

    private final UUID userId = UUID.randomUUID();

    private PredictionSubmittedEvent event(UUID sessionId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-27T09:30:00Z");
        return new PredictionSubmittedEvent(sessionId, userId, now, now.minusDays(40));
    }

    private ReplayImmediateCollectionListener listener() {
        return new ReplayImmediateCollectionListener(sessions, scheduler);
    }

    @Test
    void replaySubmissionCollectsThatSessionImmediately() {
        LearningSession replay = LearningSession.create(userId, UUID.randomUUID(), LearningSession.SessionType.replay);
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.of(replay));

        listener().handlePredictionSubmitted(event(id));

        verify(scheduler).collectNow(id);
    }

    @Test
    void liveSubmissionWaitsForScheduledCollection() {
        LearningSession live = LearningSession.create(userId, UUID.randomUUID(), LearningSession.SessionType.live);
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.of(live));

        listener().handlePredictionSubmitted(event(id));

        verify(scheduler, never()).collectNow(any());
    }

    @Test
    void missingSessionOrCollectionFailureDoesNotPropagate() {
        UUID missing = UUID.randomUUID();
        when(sessions.findById(missing)).thenReturn(Optional.empty());
        listener().handlePredictionSubmitted(event(missing));
        verify(scheduler, never()).collectNow(any());

        LearningSession replay = LearningSession.create(userId, UUID.randomUUID(), LearningSession.SessionType.replay);
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.of(replay));
        doThrow(new IllegalStateException("provider down")).when(scheduler).collectNow(id);

        assertDoesNotThrow(() -> listener().handlePredictionSubmitted(event(id)));
    }
}
