package com.finsight.learning;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionStatusService;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningSessionStatusServiceTest {
    @Mock LearningSessionRepository sessions;
    @Mock SessionTargetRepository targets;
    @Mock SessionTargetBaselineRepository baselines;
    @Mock MarketObservationRepository observations;

    LearningSessionStatusService service;
    UUID sessionId;
    LearningSession session;
    SessionTarget first;
    SessionTarget second;

    @BeforeEach
    void setUp() {
        service = new LearningSessionStatusService(sessions, targets, baselines, observations);
        sessionId = UUID.randomUUID();
        session = LearningSession.create(UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(session, "id", sessionId);
        first = selected(1);
        second = selected(2);
    }

    private SessionTarget selected(int order) {
        SessionTarget target = SessionTarget.create(sessionId, UUID.randomUUID(), order);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        return target;
    }

    private void submittedWith(List<SessionTarget> chosen) {
        session.markSubmitted(OffsetDateTime.now());
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(targets.findAllByLearningSessionId(sessionId)).thenReturn(chosen);
    }

    private void baseline(SessionTarget target, SessionTargetBaseline.BaselineStatus status) {
        SessionTargetBaseline row = new SessionTargetBaseline();
        ReflectionTestUtils.setField(row, "status", status);
        when(baselines.findBySessionTargetId(target.getId())).thenReturn(Optional.of(row));
    }

    private void marketResults(SessionTarget target, MarketObservation.OutcomeStatus... statuses) {
        int[] horizons = {1, 5, 20};
        List<MarketObservation> rows = java.util.stream.IntStream.range(0, statuses.length)
                .mapToObj(i -> {
                    MarketObservation row = new MarketObservation();
                    ReflectionTestUtils.setField(row, "horizon", horizons[i]);
                    ReflectionTestUtils.setField(row, "status", statuses[i]);
                    return row;
                }).toList();
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(target.getId())).thenReturn(rows);
    }

    @Test
    void pendingBaselineOrMissingBaselineDoesNotAdvance() {
        submittedWith(List.of(first));
        baseline(first, SessionTargetBaseline.BaselineStatus.pending);
        assertEquals(LearningSession.LearningStatus.baseline_pending, service.reconcile(sessionId));
        verifyNoInteractions(observations);
    }

    @Test
    void readyBaselineWithUnfinishedOutcomesEntersObserving() {
        submittedWith(List.of(first));
        baseline(first, SessionTargetBaseline.BaselineStatus.ready);
        marketResults(first, MarketObservation.OutcomeStatus.ready, MarketObservation.OutcomeStatus.pending);
        assertEquals(LearningSession.LearningStatus.observing, service.reconcile(sessionId));
    }

    @Test
    void allHorizonResultsEndEvenIfSomeUnavailable() {
        submittedWith(List.of(first));
        baseline(first, SessionTargetBaseline.BaselineStatus.ready);
        marketResults(first, MarketObservation.OutcomeStatus.ready,
                MarketObservation.OutcomeStatus.unavailable, MarketObservation.OutcomeStatus.ready);
        assertEquals(LearningSession.LearningStatus.reflection_pending, service.reconcile(sessionId));
    }

    @Test
    void unavailableT0RequiresThreeUnavailableObservations() {
        submittedWith(List.of(first));
        baseline(first, SessionTargetBaseline.BaselineStatus.unavailable);
        marketResults(first, MarketObservation.OutcomeStatus.unavailable,
                MarketObservation.OutcomeStatus.unavailable, MarketObservation.OutcomeStatus.unavailable);
        assertEquals(LearningSession.LearningStatus.reflection_pending, service.reconcile(sessionId));
    }

    @Test
    void doesNotFinishIfAnySelectedTargetMissingT20() {
        submittedWith(List.of(first, second));
        baseline(first, SessionTargetBaseline.BaselineStatus.ready);
        baseline(second, SessionTargetBaseline.BaselineStatus.ready);
        marketResults(first, MarketObservation.OutcomeStatus.ready,
                MarketObservation.OutcomeStatus.ready, MarketObservation.OutcomeStatus.ready);
        marketResults(second, MarketObservation.OutcomeStatus.ready,
                MarketObservation.OutcomeStatus.ready);
        assertEquals(LearningSession.LearningStatus.observing, service.reconcile(sessionId));
    }

    @Test
    void observingCanAdvanceAndCannotMoveBackward() {
        submittedWith(List.of(first));
        baseline(first, SessionTargetBaseline.BaselineStatus.ready);
        marketResults(first, MarketObservation.OutcomeStatus.ready);
        assertEquals(LearningSession.LearningStatus.observing, service.reconcile(sessionId));
        marketResults(first, MarketObservation.OutcomeStatus.ready,
                MarketObservation.OutcomeStatus.ready, MarketObservation.OutcomeStatus.ready);
        assertEquals(LearningSession.LearningStatus.reflection_pending, service.reconcile(sessionId));
        assertEquals(LearningSession.LearningStatus.reflection_pending, service.reconcile(sessionId));
    }

    @Test
    void draftingAndCompletedAreNotChangedByMarketReconciliation() {
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        assertEquals(LearningSession.LearningStatus.drafting, service.reconcile(sessionId));
        session.markSubmitted(OffsetDateTime.now());
        session.markCompleted(OffsetDateTime.now());
        assertEquals(LearningSession.LearningStatus.completed, service.reconcile(sessionId));
        verifyNoInteractions(targets, baselines, observations);
    }
}
