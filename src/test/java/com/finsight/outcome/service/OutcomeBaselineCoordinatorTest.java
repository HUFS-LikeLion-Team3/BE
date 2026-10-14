package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutcomeBaselineCoordinatorTest {

    private static final ZoneId NEW_YORK =
            ZoneId.of("America/New_York");

    private final UUID sessionId = UUID.randomUUID();
    private final UUID sessionTargetId = UUID.randomUUID();
    private final UUID marketTargetId = UUID.randomUUID();

    private LearningSessionRepository sessionRepository;
    private SessionTargetRepository sessionTargetRepository;
    private MarketTargetRepository marketTargetRepository;
    private SessionTargetBaselineRepository baselineRepository;

    private MarketDataService marketDataService;
    private OutcomeScheduleService scheduleService;
    private OutcomeCollectionService collectionService;

    private OutcomeBaselineCoordinator coordinator;

    private LearningSession session;
    private SessionTarget selectedTarget;
    private MarketTarget marketTarget;

    private OffsetDateTime submittedAt;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(LearningSessionRepository.class);
        sessionTargetRepository = mock(SessionTargetRepository.class);
        marketTargetRepository = mock(MarketTargetRepository.class);
        baselineRepository = mock(SessionTargetBaselineRepository.class);

        marketDataService = mock(MarketDataService.class);
        scheduleService = mock(OutcomeScheduleService.class);
        collectionService = mock(OutcomeCollectionService.class);

        session = mock(LearningSession.class);
        selectedTarget = mock(SessionTarget.class);
        marketTarget = mock(MarketTarget.class);

        today = LocalDate.now(NEW_YORK);
        submittedAt = OffsetDateTime.now(NEW_YORK).minusDays(1);

        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getId()).thenReturn(sessionId);
        when(session.getSubmittedAt()).thenReturn(submittedAt);

        when(session.getSessionType())
                .thenReturn(LearningSession.SessionType.live);

        when(session.getStatus())
                .thenReturn(
                        LearningSession.LearningStatus.baseline_pending
                );

        when(sessionTargetRepository
                .findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selectedTarget));

        when(selectedTarget.isSelected()).thenReturn(true);
        when(selectedTarget.getId()).thenReturn(sessionTargetId);
        when(selectedTarget.getMarketTargetId())
                .thenReturn(marketTargetId);

        when(marketTargetRepository.findById(marketTargetId))
                .thenReturn(Optional.of(marketTarget));

        when(marketTarget.getCalendarCode()).thenReturn("XNAS");
        when(marketTarget.getTimezone())
                .thenReturn("America/New_York");

        coordinator = new OutcomeBaselineCoordinator(
                sessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                marketDataService,
                scheduleService,
                collectionService
        );
    }

    private SessionTargetBaseline pendingBaseline(
            LocalDate expected,
            LocalDate deadline
    ) {
        SessionTargetBaseline baseline =
                mock(SessionTargetBaseline.class);

        when(baseline.getStatus())
                .thenReturn(SessionTargetBaseline.BaselineStatus.pending);

        when(baseline.getExpectedBaselineDate())
                .thenReturn(expected);

        when(baseline.getBaselineDeadlineDate())
                .thenReturn(deadline);

        return baseline;
    }

    private void mockOpenSession(LocalDate date) {
        when(marketDataService.regularSessionOn(
                marketTarget,
                date
        )).thenReturn(new MarketDataProvider.Session(
                date,
                NEW_YORK,
                LocalTime.of(9, 30),
                LocalTime.of(16, 0)
        ));
    }

    @Test
    void createsPendingBaselineWhenMissing() {
        LocalDate expected = today.plusDays(1);
        LocalDate deadline = today.plusDays(15);

        SessionTargetBaseline baseline =
                pendingBaseline(expected, deadline);

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(
                Optional.empty(),
                Optional.of(baseline)
        );

        when(scheduleService.calculateBaselineSchedule(
                marketTarget,
                submittedAt
        )).thenReturn(
                new OutcomeScheduleService.BaselineSchedule(
                        expected,
                        deadline
                )
        );

        List<OutcomeBaselineCoordinator.BaselineAttempt> result =
                coordinator.runOnce(sessionId);

        assertEquals(1, result.size());

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.WAITING,
                result.getFirst().status()
        );

        verify(collectionService).initializeBaseline(
                sessionId,
                sessionTargetId,
                expected,
                deadline
        );

        verify(collectionService, never()).collectBaseline(
                any(),
                any(),
                any()
        );
    }

    @Test
    void skipsAlreadyFinalizedBaseline() {
        SessionTargetBaseline baseline =
                mock(SessionTargetBaseline.class);

        when(baseline.getStatus())
                .thenReturn(SessionTargetBaseline.BaselineStatus.ready);

        when(baseline.getBaselineSessionDate())
                .thenReturn(today.minusDays(1));

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.ALREADY_FINALIZED,
                result.getFirst().status()
        );

        verifyNoInteractions(
                collectionService,
                scheduleService,
                marketDataService
        );
    }

    @Test
    void collectsReadyBaseline() {
        SessionTargetBaseline baseline =
                pendingBaseline(today, today.plusDays(15));

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        mockOpenSession(today);

        when(collectionService.collectBaseline(
                sessionId,
                sessionTargetId,
                today
        )).thenReturn(MarketDataProvider.Status.ready);

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.READY,
                result.getFirst().status()
        );

        assertEquals(
                today,
                result.getFirst().checkedDate()
        );

        verify(collectionService).collectBaseline(
                sessionId,
                sessionTargetId,
                today
        );
    }

    @Test
    void retriesWhenMarketDataIsPending() {
        SessionTargetBaseline baseline =
                pendingBaseline(today, today.plusDays(15));

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        mockOpenSession(today);

        when(collectionService.collectBaseline(
                sessionId,
                sessionTargetId,
                today
        )).thenReturn(MarketDataProvider.Status.pending);

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.RETRY_LATER,
                result.getFirst().status()
        );

        verify(collectionService).collectBaseline(
                sessionId,
                sessionTargetId,
                today
        );
    }

    @Test
    void missingMarketDataDoesNotCreateFakeBaseline() {
        SessionTargetBaseline baseline =
                pendingBaseline(today, today.plusDays(15));

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        mockOpenSession(today);

        when(collectionService.collectBaseline(
                sessionId,
                sessionTargetId,
                today
        )).thenReturn(MarketDataProvider.Status.unavailable);

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.WAITING,
                result.getFirst().status()
        );

        verify(collectionService, never()).expireBaseline(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsUnsupportedDailyMarket() {
        when(marketTarget.getCalendarCode())
                .thenReturn("USTY");

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .UNSUPPORTED_DAILY_MARKET,
                result.getFirst().status()
        );

        verifyNoInteractions(
                collectionService,
                scheduleService,
                marketDataService
        );
    }

    @Test
    void doesNotTreatCalendarFailureAsMarketHoliday() {
        SessionTargetBaseline baseline =
                pendingBaseline(today, today.plusDays(15));

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        when(marketDataService.regularSessionOn(
                marketTarget,
                today
        )).thenThrow(
                new MarketDataProvider.DataException(
                        MarketDataProvider.Status.pending,
                        "calendar_temporarily_unavailable"
                )
        );

        var result = coordinator.runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.RETRY_LATER,
                result.getFirst().status()
        );

        verify(collectionService, never()).collectBaseline(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsUnsubmittedSession() {
        when(session.getSubmittedAt()).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.runOnce(sessionId)
        );

        verifyNoInteractions(
                collectionService,
                scheduleService,
                marketDataService
        );
    }
}