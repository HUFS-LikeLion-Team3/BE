package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutcomeObservationCollectionSchedulerTest {

    private static final ZoneId NEW_YORK =
            ZoneId.of("America/New_York");

    private LearningSessionRepository sessionRepository;
    private SessionTargetRepository sessionTargetRepository;
    private MarketTargetRepository marketTargetRepository;
    private SessionTargetBaselineRepository baselineRepository;
    private MarketObservationRepository observationRepository;

    private MarketDataService marketDataService;
    private OutcomeCollectionService collectionService;

    private OutcomeObservationCollectionScheduler scheduler;

    private UUID sessionId;
    private UUID sessionTargetId;
    private UUID marketTargetId;
    private UUID baselineId;

    private LearningSession session;
    private SessionTarget selectedTarget;
    private MarketTarget marketTarget;
    private SessionTargetBaseline baseline;
    private MarketObservation observation;

    private LocalDate expectedDate;
    private LocalDate deadlineDate;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(LearningSessionRepository.class);
        sessionTargetRepository = mock(SessionTargetRepository.class);
        marketTargetRepository = mock(MarketTargetRepository.class);
        baselineRepository = mock(SessionTargetBaselineRepository.class);
        observationRepository = mock(MarketObservationRepository.class);

        marketDataService = mock(MarketDataService.class);
        collectionService = mock(OutcomeCollectionService.class);

        sessionId = UUID.randomUUID();
        sessionTargetId = UUID.randomUUID();
        marketTargetId = UUID.randomUUID();
        baselineId = UUID.randomUUID();

        session = mock(LearningSession.class);
        selectedTarget = mock(SessionTarget.class);
        marketTarget = mock(MarketTarget.class);
        baseline = mock(SessionTargetBaseline.class);
        observation = mock(MarketObservation.class);

        expectedDate = LocalDate.now(NEW_YORK)
                .minusDays(60)
                .with(TemporalAdjusters.previousOrSame(
                        DayOfWeek.FRIDAY
                ));

        deadlineDate = expectedDate.plusDays(14);

        when(session.getId()).thenReturn(sessionId);
        when(session.getSubmittedAt()).thenReturn(
                OffsetDateTime.parse("2026-01-01T10:00:00+09:00")
        );
        when(session.getSessionType()).thenReturn(
                LearningSession.SessionType.live
        );
        when(session.getStatus()).thenReturn(
                LearningSession.LearningStatus.observing
        );

        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));

        when(sessionRepository.findAllByStatusIn(
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(session)));

        when(selectedTarget.getId()).thenReturn(sessionTargetId);
        when(selectedTarget.isSelected()).thenReturn(true);
        when(selectedTarget.getMarketTargetId())
                .thenReturn(marketTargetId);

        when(sessionTargetRepository
                .findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selectedTarget));

        when(marketTargetRepository.findById(marketTargetId))
                .thenReturn(Optional.of(marketTarget));

        when(marketTarget.getCalendarCode()).thenReturn("XNAS");
        when(marketTarget.getTimezone())
                .thenReturn("America/New_York");

        when(baselineRepository.findBySessionTargetId(
                sessionTargetId
        )).thenReturn(Optional.of(baseline));

        when(baseline.getId()).thenReturn(baselineId);
        when(baseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.ready
        );
        when(baseline.getUnit()).thenReturn("USD");

        when(observation.getHorizon()).thenReturn(1);
        when(observation.getStatus()).thenReturn(
                MarketObservation.OutcomeStatus.pending
        );
        when(observation.getBaselineId()).thenReturn(baselineId);
        when(observation.getUnit()).thenReturn("USD");
        when(observation.getExpectedObservationDate())
                .thenReturn(expectedDate);
        when(observation.getObservationDeadlineDate())
                .thenReturn(deadlineDate);

        when(observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(
                        sessionTargetId
                )).thenReturn(List.of(observation));

        scheduler = new OutcomeObservationCollectionScheduler(
                sessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                observationRepository,
                marketDataService,
                collectionService
        );

        mockTradingCalendar();
    }

    private void mockTradingCalendar() {
        when(marketDataService.regularSessionOn(
                eq(marketTarget),
                any(LocalDate.class)
        )).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);

            boolean weekend =
                    date.getDayOfWeek() == DayOfWeek.SATURDAY
                            || date.getDayOfWeek() == DayOfWeek.SUNDAY;

            if (weekend) {
                return new MarketDataProvider.Session(
                        date,
                        NEW_YORK,
                        null,
                        null
                );
            }

            return new MarketDataProvider.Session(
                    date,
                    NEW_YORK,
                    LocalTime.of(9, 30),
                    LocalTime.of(16, 0)
            );
        });
    }

    @Test
    void collectsReadyObservation() {
        when(collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                expectedDate,
                0
        )).thenReturn(MarketDataProvider.Status.ready);

        scheduler.collectObservations();

        verify(collectionService).collectObservation(
                sessionId,
                sessionTargetId,
                1,
                expectedDate,
                0
        );

        verify(collectionService, never()).expireObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyString()
        );
    }

    @Test
    void retriesWhenObservationIsPending() {
        when(collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                expectedDate,
                0
        )).thenReturn(MarketDataProvider.Status.pending);

        scheduler.collectObservations();

        verify(collectionService, times(1))
                .collectObservation(
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        anyInt()
                );

        verify(collectionService, never()).expireObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyString()
        );
    }

    @Test
    void skipsWeekendAndCountsDelaySessions() {
        LocalDate monday = expectedDate.plusDays(3);

        when(collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                expectedDate,
                0
        )).thenReturn(MarketDataProvider.Status.unavailable);

        when(collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                monday,
                1
        )).thenReturn(MarketDataProvider.Status.ready);

        scheduler.collectObservations();

        verify(collectionService).collectObservation(
                sessionId,
                sessionTargetId,
                1,
                expectedDate,
                0
        );

        verify(collectionService).collectObservation(
                sessionId,
                sessionTargetId,
                1,
                monday,
                1
        );

        verify(collectionService, times(2))
                .collectObservation(
                        any(),
                        any(),
                        anyInt(),
                        any(),
                        anyInt()
                );
    }

    @Test
    void expiresObservationAfterDeadline() {
        when(collectionService.collectObservation(
                any(),
                any(),
                anyInt(),
                any(LocalDate.class),
                anyInt()
        )).thenReturn(MarketDataProvider.Status.unavailable);

        scheduler.collectObservations();

        verify(collectionService).expireObservation(
                sessionId,
                sessionTargetId,
                1,
                MarketObservation.OutcomeExceptionStatus.data_missing,
                "OBSERVATION_DEADLINE_EXCEEDED"
        );
    }

    @Test
    void doesNotCollectFutureObservation() {
        LocalDate futureDate =
                LocalDate.now(NEW_YORK).plusDays(5);

        when(observation.getExpectedObservationDate())
                .thenReturn(futureDate);

        when(observation.getObservationDeadlineDate())
                .thenReturn(futureDate.plusDays(15));

        scheduler.collectObservations();

        verify(collectionService, never()).collectObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyInt()
        );

        verify(collectionService, never()).expireObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyString()
        );
    }

    @Test
    void skipsAlreadyFinalizedObservation() {
        when(observation.getStatus()).thenReturn(
                MarketObservation.OutcomeStatus.ready
        );

        scheduler.collectObservations();

        verifyNoInteractions(collectionService);
    }

    @Test
    void skipsUnavailableBaseline() {
        when(baseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.unavailable
        );

        scheduler.collectObservations();

        verifyNoInteractions(
                observationRepository,
                collectionService
        );
    }

    @Test
    void skipsUnsupportedDailyMarket() {
        when(marketTarget.getCalendarCode()).thenReturn("USTY");

        scheduler.collectObservations();

        verifyNoInteractions(
                observationRepository,
                collectionService
        );
    }

    @Test
    void doesNotTreatProviderFailureAsMissingObservation() {
        when(marketDataService.regularSessionOn(
                eq(marketTarget),
                eq(expectedDate)
        )).thenThrow(
                new MarketDataProvider.DataException(
                        MarketDataProvider.Status.pending,
                        "calendar_temporarily_unavailable"
                )
        );

        scheduler.collectObservations();

        verify(collectionService, never()).collectObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyInt()
        );

        verify(collectionService, never()).expireObservation(
                any(),
                any(),
                anyInt(),
                any(),
                anyString()
        );
    }

    @Test
    void skipsUnsubmittedSession() {
        when(session.getSubmittedAt()).thenReturn(null);

        scheduler.collectObservations();

        verifyNoInteractions(
                sessionTargetRepository,
                collectionService
        );
    }

    @Test
    void emptyCandidateListDoesNothing() {
        when(sessionRepository.findAllByStatusIn(
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of()));

        scheduler.collectObservations();

        verifyNoInteractions(
                sessionTargetRepository,
                collectionService
        );
    }
}