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
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyOutcomeSchedulerTest {

    @Test
    void countsActualPublishedDaysForAllHorizons() {
        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID marketId = UUID.randomUUID();
        UUID baselineId = UUID.randomUUID();

        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate t0 = LocalDate.now(zone).minusDays(70);
        LocalDate missingDay = addWeekdays(t0, 1);

        LearningSessionRepository sessions = mock(LearningSessionRepository.class);
        SessionTargetRepository targets = mock(SessionTargetRepository.class);
        MarketTargetRepository markets = mock(MarketTargetRepository.class);
        SessionTargetBaselineRepository baselines = mock(SessionTargetBaselineRepository.class);
        MarketObservationRepository observations = mock(MarketObservationRepository.class);
        MarketDataService data = mock(MarketDataService.class);
        OutcomeCollectionService collector = mock(OutcomeCollectionService.class);

        LearningSession session = mock(LearningSession.class);
        SessionTarget selected = mock(SessionTarget.class);
        MarketTarget market = mock(MarketTarget.class);
        SessionTargetBaseline baseline = mock(SessionTargetBaseline.class);

        when(session.getId()).thenReturn(sessionId);
        when(session.getSubmittedAt()).thenReturn(
                OffsetDateTime.now().minusDays(75));
        when(session.getSessionType()).thenReturn(LearningSession.SessionType.live);
        when(session.getStatus()).thenReturn(LearningSession.LearningStatus.observing);

        when(sessions.findAllByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session)));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        when(selected.isSelected()).thenReturn(true);
        when(selected.getId()).thenReturn(targetId);
        when(selected.getMarketTargetId()).thenReturn(marketId);
        when(targets.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selected));

        when(markets.findById(marketId)).thenReturn(Optional.of(market));
        when(market.getCalendarCode()).thenReturn("USTY");
        when(market.getTimezone()).thenReturn(zone.getId());

        when(baselines.findBySessionTargetId(targetId))
                .thenReturn(Optional.of(baseline));
        when(baseline.getId()).thenReturn(baselineId);
        when(baseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.ready);
        when(baseline.getBaselineSessionDate()).thenReturn(t0);
        when(baseline.getBaselineAt()).thenReturn(
                t0.atTime(15, 30).atZone(zone).toOffsetDateTime());
        when(baseline.getUnit()).thenReturn("%");

        List<MarketObservation> rows = new ArrayList<>();

        for (int horizon : List.of(1, 5, 20)) {
            MarketObservation row = mock(MarketObservation.class);
            LocalDate expected = addWeekdays(t0, horizon);

            when(row.getHorizon()).thenReturn(horizon);
            when(row.getStatus()).thenReturn(MarketObservation.OutcomeStatus.pending);
            when(row.getBaselineId()).thenReturn(baselineId);
            when(row.getUnit()).thenReturn("%");
            when(row.getExpectedObservationDate()).thenReturn(expected);
            when(row.getObservationDeadlineDate()).thenReturn(
                    addWeekdays(expected, 10));

            rows.add(row);
        }

        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(rows);

        when(data.regularCloseOn(eq(market), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(1);

                    if (!weekday(date) || date.equals(missingDay)) {
                        return new MarketDataService.Result<>(
                                MarketDataProvider.Status.unavailable,
                                null,
                                "missing_requested_observation"
                        );
                    }

                    return new MarketDataService.Result<>(
                            MarketDataProvider.Status.ready,
                            new MarketDataService.Close(
                                    date,
                                    date.atTime(15, 30).atZone(zone).toOffsetDateTime(),
                                    new BigDecimal("4.25"),
                                    "%",
                                    "Test Treasury",
                                    "https://example.com"
                            )
                    );
                });

        when(collector.collectObservation(
                eq(sessionId), eq(targetId), anyInt(),
                any(LocalDate.class), anyInt()
        )).thenReturn(MarketDataProvider.Status.ready);

        new DailyOutcomeScheduler(
                sessions, targets, markets, baselines,
                observations, data, collector
        ).run();

        List<LocalDate> published = new ArrayList<>();

        for (LocalDate d = t0.plusDays(1);
             published.size() < 20;
             d = d.plusDays(1)) {
            if (weekday(d) && !d.equals(missingDay)) {
                published.add(d);
            }
        }

        verify(collector).collectObservation(
                sessionId, targetId, 1, published.get(0), 1);
        verify(collector).collectObservation(
                sessionId, targetId, 5, published.get(4), 1);
        verify(collector).collectObservation(
                sessionId, targetId, 20, published.get(19), 1);
        verify(collector, times(3)).collectObservation(
                eq(sessionId), eq(targetId), anyInt(),
                any(LocalDate.class), anyInt());
    }

    @Test
    void expiresBaselineAfterTenPublishedSessions() {
        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID marketId = UUID.randomUUID();

        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate start = LocalDate.now(zone).minusDays(45);

        LearningSessionRepository sessions = mock(LearningSessionRepository.class);
        SessionTargetRepository targets = mock(SessionTargetRepository.class);
        MarketTargetRepository markets = mock(MarketTargetRepository.class);
        SessionTargetBaselineRepository baselines = mock(SessionTargetBaselineRepository.class);
        MarketObservationRepository observations = mock(MarketObservationRepository.class);
        MarketDataService data = mock(MarketDataService.class);
        OutcomeCollectionService collector = mock(OutcomeCollectionService.class);

        LearningSession session = mock(LearningSession.class);
        SessionTarget selected = mock(SessionTarget.class);
        MarketTarget market = mock(MarketTarget.class);
        SessionTargetBaseline baseline = mock(SessionTargetBaseline.class);

        when(session.getId()).thenReturn(sessionId);
        when(session.getSubmittedAt()).thenReturn(
                OffsetDateTime.now().minusDays(50));
        when(session.getSessionType()).thenReturn(LearningSession.SessionType.live);
        when(session.getStatus()).thenReturn(
                LearningSession.LearningStatus.baseline_pending);

        when(sessions.findAllByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(session)));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        when(selected.isSelected()).thenReturn(true);
        when(selected.getId()).thenReturn(targetId);
        when(selected.getMarketTargetId()).thenReturn(marketId);
        when(targets.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selected));

        when(markets.findById(marketId)).thenReturn(Optional.of(market));
        when(market.getCalendarCode()).thenReturn("USTY");
        when(market.getTimezone()).thenReturn(zone.getId());

        when(baselines.findBySessionTargetId(targetId))
                .thenReturn(Optional.of(baseline));
        when(baseline.getStatus()).thenReturn(
                SessionTargetBaseline.BaselineStatus.pending);
        when(baseline.getExpectedBaselineDate()).thenReturn(start);

        when(data.regularCloseOn(eq(market), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(1);

                    if (!weekday(date)) {
                        return new MarketDataService.Result<>(
                                MarketDataProvider.Status.unavailable,
                                null,
                                "missing_requested_observation"
                        );
                    }

                    return new MarketDataService.Result<>(
                            MarketDataProvider.Status.ready,
                            new MarketDataService.Close(
                                    date,
                                    date.atTime(15, 30).atZone(zone)
                                            .toOffsetDateTime(),
                                    new BigDecimal("4.25"),
                                    "%",
                                    "Test Treasury",
                                    "https://example.com"
                            )
                    );
                });

        when(collector.collectBaseline(
                eq(sessionId), eq(targetId), any(LocalDate.class)))
                .thenReturn(MarketDataProvider.Status.pending);

        new DailyOutcomeScheduler(
                sessions, targets, markets, baselines,
                observations, data, collector
        ).run();

        verify(collector).expireBaseline(
                sessionId,
                targetId,
                "BASELINE_10_PUBLISHED_SESSIONS_EXCEEDED"
        );
    }
    private static LocalDate addWeekdays(LocalDate date, int count) {
        while (count > 0) {
            date = date.plusDays(1);
            if (weekday(date)) count--;
        }
        return date;
    }

    private static boolean weekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}