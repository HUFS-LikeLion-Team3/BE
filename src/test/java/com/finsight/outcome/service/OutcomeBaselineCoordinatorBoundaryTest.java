
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutcomeBaselineCoordinatorBoundaryTest {

    private static final LocalDate DEADLINE =
            LocalDate.of(2026, 10, 12);

    private static final ZoneId NEW_YORK =
            ZoneId.of("America/New_York");

    private final UUID sessionId = UUID.randomUUID();
    private final UUID sessionTargetId = UUID.randomUUID();
    private final UUID marketTargetId = UUID.randomUUID();

    @Mock
    LearningSessionRepository sessionRepository;

    @Mock
    SessionTargetRepository sessionTargetRepository;

    @Mock
    MarketTargetRepository marketTargetRepository;

    @Mock
    SessionTargetBaselineRepository baselineRepository;

    @Mock
    MarketDataService marketDataService;

    @Mock
    OutcomeScheduleService scheduleService;

    @Mock
    OutcomeCollectionService collectionService;

    @Mock
    LearningSession session;

    @Mock
    SessionTarget sessionTarget;

    @Mock
    MarketTarget marketTarget;

    @Mock
    SessionTargetBaseline baseline;

    @Test
    void beforeDeadlineAllowsConfirmedBaseline() {
        preparePendingBaseline();

        when(marketDataService.regularSessionOn(
                marketTarget, DEADLINE
        )).thenReturn(openSession());

        when(collectionService.collectBaseline(
                sessionId, sessionTargetId, DEADLINE
        )).thenReturn(MarketDataProvider.Status.ready);

        var result = coordinator(
                "2026-10-12T20:14:00Z"
        ).runOnce(sessionId);

        assertEquals(1, result.size());

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.READY,
                result.getFirst().status()
        );

        verify(collectionService).collectBaseline(
                sessionId,
                sessionTargetId,
                DEADLINE
        );
    }

    @Test
    void pendingBeforeDeadlineMustRetry() {
        preparePendingBaseline();

        when(marketDataService.regularSessionOn(
                marketTarget, DEADLINE
        )).thenReturn(openSession());

        when(collectionService.collectBaseline(
                sessionId, sessionTargetId, DEADLINE
        )).thenReturn(MarketDataProvider.Status.pending);

        var result = coordinator(
                "2026-10-12T20:14:00Z"
        ).runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus.RETRY_LATER,
                result.getFirst().status()
        );
    }

    @Test
    void exactlyAtDeadlineMustExpire() {
        preparePendingBaseline();

        when(marketDataService.regularSessionOn(
                marketTarget, DEADLINE
        )).thenReturn(openSession());

        var result = coordinator(
                "2026-10-12T20:15:00Z"
        ).runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .DEADLINE_PASSED_UNRESOLVED,
                result.getFirst().status()
        );

        verify(collectionService, never())
                .collectBaseline(any(), any(), any());
    }

    @Test
    void lateDataAfterDeadlineMustNotBeCollected() {
        preparePendingBaseline();

        when(marketDataService.regularSessionOn(
                marketTarget, DEADLINE
        )).thenReturn(openSession());

        var result = coordinator(
                "2026-10-12T20:16:00Z"
        ).runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .DEADLINE_PASSED_UNRESOLVED,
                result.getFirst().status()
        );

        verify(collectionService, never())
                .collectBaseline(any(), any(), any());
    }

    @Test
    void nextDayMustExpireWithoutMarketQuery() {
        preparePendingBaseline();

        var result = coordinator(
                "2026-10-13T16:00:00Z"
        ).runOnce(sessionId);

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .DEADLINE_PASSED_UNRESOLVED,
                result.getFirst().status()
        );

        verifyNoInteractions(marketDataService);
        verify(collectionService, never())
                .collectBaseline(any(), any(), any());
    }

    @Test
    void failureOfOneTargetDoesNotStopAnother() {
        SessionTarget secondTarget = mock(SessionTarget.class);
        MarketTarget secondMarket = mock(MarketTarget.class);
        SessionTargetBaseline secondBaseline =
                mock(SessionTargetBaseline.class);

        UUID secondTargetId = UUID.randomUUID();
        UUID secondMarketId = UUID.randomUUID();

        prepareSession();

        when(sessionTargetRepository.findAllByLearningSessionId(
                sessionId
        )).thenReturn(List.of(sessionTarget, secondTarget));

        when(sessionTarget.isSelected()).thenReturn(true);
        when(sessionTarget.getId()).thenReturn(sessionTargetId);
        when(sessionTarget.getMarketTargetId())
                .thenReturn(marketTargetId);

        when(marketTargetRepository.findById(
                marketTargetId
        )).thenReturn(Optional.empty());

        when(secondTarget.isSelected()).thenReturn(true);
        when(secondTarget.getId()).thenReturn(secondTargetId);
        when(secondTarget.getMarketTargetId())
                .thenReturn(secondMarketId);

        when(marketTargetRepository.findById(
                secondMarketId
        )).thenReturn(Optional.of(secondMarket));

        when(secondMarket.getCalendarCode()).thenReturn("XNAS");

        when(baselineRepository.findBySessionTargetId(
                secondTargetId
        )).thenReturn(Optional.of(secondBaseline));

        when(secondBaseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.ready
        );

        var result = coordinator(
                "2026-10-12T20:14:00Z"
        ).runOnce(sessionId);

        assertEquals(2, result.size());

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .PROVIDER_UNAVAILABLE,
                result.get(0).status()
        );

        assertEquals(
                OutcomeBaselineCoordinator.AttemptStatus
                        .ALREADY_FINALIZED,
                result.get(1).status()
        );
    }

    private void prepareSession() {
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getSubmittedAt()).thenReturn(
                OffsetDateTime.parse("2026-10-09T12:00:00Z")
        );

        when(session.getSessionType()).thenReturn(
                LearningSession.SessionType.live
        );

        when(session.getStatus()).thenReturn(
                LearningSession.LearningStatus.baseline_pending
        );
    }

    private void preparePendingBaseline() {
        prepareSession();

        when(sessionTargetRepository.findAllByLearningSessionId(
                sessionId
        )).thenReturn(List.of(sessionTarget));

        when(sessionTarget.isSelected()).thenReturn(true);
        when(sessionTarget.getId()).thenReturn(sessionTargetId);
        when(sessionTarget.getMarketTargetId())
                .thenReturn(marketTargetId);

        when(marketTargetRepository.findById(
                marketTargetId
        )).thenReturn(Optional.of(marketTarget));

        when(marketTarget.getCalendarCode()).thenReturn("XNAS");
        when(marketTarget.getTimezone())
                .thenReturn(NEW_YORK.getId());

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        when(baseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.pending
        );

        when(baseline.getExpectedBaselineDate())
                .thenReturn(DEADLINE);

        when(baseline.getBaselineDeadlineDate())
                .thenReturn(DEADLINE);
    }

    private MarketDataProvider.Session openSession() {
        return new MarketDataProvider.Session(
                DEADLINE,
                NEW_YORK,
                LocalTime.of(9, 30),
                LocalTime.of(16, 0)
        );
    }

    private OutcomeBaselineCoordinator coordinator(
            String fixedInstant
    ) {
        Clock clock = Clock.fixed(
                Instant.parse(fixedInstant),
                ZoneOffset.UTC
        );

        return new OutcomeBaselineCoordinator(
                sessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                marketDataService,
                scheduleService,
                collectionService,
                Duration.ofMinutes(15),
                clock
        );
    }
}
