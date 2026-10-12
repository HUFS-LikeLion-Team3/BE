
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class DailyOutcomeScheduler {

    private static final Set<String> DAILY_CODES =
            Set.of("USTY", "TDFX", "TDGD", "TDWT");

    private static final List<Integer> HORIZONS =
            List.of(1, 5, 20);

    private static final List<LearningSession.LearningStatus> ACTIVE =
            List.of(
                    LearningSession.LearningStatus.baseline_pending,
                    LearningSession.LearningStatus.observing
            );

    private static final int RETRY_WEEKDAYS = 10;

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository targetRepository;
    private final MarketTargetRepository marketRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final MarketDataService marketDataService;
    private final OutcomeCollectionService collectionService;

    @Value("${finsight.outcome.collection.page-size:100}")
    private int pageSize = 100;

    @Value("${market.data.confirmation-delay:PT15M}")
    private Duration confirmationDelay = Duration.ofMinutes(15);

    @Scheduled(
            fixedDelayString =
                    "${finsight.outcome.collection.daily-fixed-delay-ms:86400000}",
            initialDelayString =
                    "${finsight.outcome.collection.daily-initial-delay-ms:120000}"
    )
    public void run() {
        List<UUID> ids = new ArrayList<>();

        try {
            Page<LearningSession> page;
            int number = 0;

            do {
                page = sessionRepository.findAllByStatusIn(
                        ACTIVE,
                        PageRequest.of(
                                number++,
                                Math.max(1, pageSize),
                                Sort.by(Sort.Direction.ASC, "id")
                        )
                );

                page.getContent().stream()
                        .filter(s -> s.getSessionType()
                                == LearningSession.SessionType.live)
                        .filter(s -> s.getSubmittedAt() != null)
                        .map(LearningSession::getId)
                        .forEach(ids::add);

            } while (page.hasNext());

        } catch (RuntimeException e) {
            log.error("Daily market candidate lookup failed", e);
            return;
        }

        for (UUID id : ids) {
            try {
                collectSession(id);
            } catch (RuntimeException e) {
                log.warn(
                        "Daily market session failed: sessionId={}, type={}",
                        id,
                        e.getClass().getSimpleName()
                );
            }
        }
    }

    private void collectSession(UUID id) {
        LearningSession session =
                sessionRepository.findById(id).orElse(null);

        if (session == null
                || session.getSubmittedAt() == null
                || session.getSessionType()
                != LearningSession.SessionType.live
                || !ACTIVE.contains(session.getStatus())) {
            return;
        }

        for (SessionTarget selected :
                targetRepository.findAllByLearningSessionId(id)) {

            if (!selected.isSelected()) {
                continue;
            }

            try {
                MarketTarget market = marketRepository
                        .findById(selected.getMarketTargetId())
                        .orElse(null);

                if (market != null
                        && DAILY_CODES.contains(market.getCalendarCode())) {
                    collectTarget(session, selected, market);
                }

            } catch (MarketDataProvider.DataException e) {
                log.debug(
                        "Daily market data unavailable: target={}, status={}",
                        selected.getId(),
                        e.status()
                );
            } catch (RuntimeException e) {
                log.warn(
                        "Daily market target failed: target={}, type={}",
                        selected.getId(),
                        e.getClass().getSimpleName()
                );
            }
        }
    }

    private void collectTarget(
            LearningSession session,
            SessionTarget selected,
            MarketTarget market
    ) {
        UUID sessionId = session.getId();
        UUID targetId = selected.getId();

        SessionTargetBaseline baseline = baselineRepository
                .findBySessionTargetId(targetId)
                .orElse(null);

        if (baseline == null) {
            LocalDate expected =
                    firstDate(market, session.getSubmittedAt());

            collectionService.initializeBaseline(
                    sessionId,
                    targetId,
                    expected,
                    addWeekdays(expected, RETRY_WEEKDAYS)
            );

            baseline = baselineRepository
                    .findBySessionTargetId(targetId)
                    .orElse(null);
        }

        if (baseline == null) {
            return;
        }

        if (baseline.getStatus()
                == SessionTargetBaseline.BaselineStatus.pending) {

            collectBaseline(sessionId, targetId, market, baseline);

            baseline = baselineRepository
                    .findBySessionTargetId(targetId)
                    .orElse(null);
        }

        if (baseline == null
                || baseline.getStatus()
                != SessionTargetBaseline.BaselineStatus.ready
                || baseline.getBaselineSessionDate() == null
                || baseline.getBaselineAt() == null) {
            return;
        }

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(targetId);

        Set<Integer> existing = new HashSet<>();

        for (MarketObservation observation : observations) {
            existing.add(observation.getHorizon());
        }

        if (!existing.containsAll(HORIZONS)) {
            LocalDate start = baseline.getBaselineSessionDate();

            Map<Integer, LocalDate> expected = new HashMap<>();
            Map<Integer, LocalDate> deadlines = new HashMap<>();

            for (int horizon : HORIZONS) {
                LocalDate date = addWeekdays(start, horizon);

                expected.put(horizon, date);
                deadlines.put(
                        horizon,
                        addWeekdays(date, RETRY_WEEKDAYS)
                );
            }

            collectionService.initializeObservations(
                    sessionId,
                    targetId,
                    expected,
                    deadlines
            );

            observations = observationRepository
                    .findAllBySessionTargetIdOrderByHorizonAsc(targetId);
        }

        collectObservations(
                sessionId,
                targetId,
                market,
                baseline,
                observations
        );
    }

    private void collectBaseline(
            UUID sessionId,
            UUID targetId,
            MarketTarget market,
            SessionTargetBaseline baseline
    ) {
        LocalDate expected = baseline.getExpectedBaselineDate();
        LocalDate deadline = baseline.getBaselineDeadlineDate();

        if (expected == null
                || deadline == null
                || deadline.isBefore(expected)) {
            return;
        }

        LocalDate today =
                LocalDate.now(ZoneId.of(market.getTimezone()));

        if (today.isBefore(expected)) {
            return;
        }

        for (LocalDate date = expected;
             !date.isAfter(today) && !date.isAfter(deadline);
             date = date.plusDays(1)) {

            if (!isFinalized(market, date)) {
                return;
            }

            var result = marketDataService.regularCloseOn(
                    market,
                    date
            );

            if (result.status() == MarketDataProvider.Status.ready) {
                collectionService.collectBaseline(
                        sessionId,
                        targetId,
                        date
                );
                return;
            }

            if (result.status() == MarketDataProvider.Status.pending
                    || !missing(result)) {
                return;
            }
        }

        if (today.isAfter(deadline)) {
            collectionService.expireBaseline(
                    sessionId,
                    targetId,
                    "BASELINE_DEADLINE_EXCEEDED"
            );
        }
    }

    private void collectObservations(
            UUID sessionId,
            UUID targetId,
            MarketTarget market,
            SessionTargetBaseline baseline,
            List<MarketObservation> observations
    ) {
        List<MarketObservation> pending = observations.stream()
                .filter(o -> HORIZONS.contains(o.getHorizon()))
                .filter(o -> o.getStatus()
                        == MarketObservation.OutcomeStatus.pending)
                .toList();

        if (pending.isEmpty()) {
            return;
        }

        for (MarketObservation observation : pending) {
            if (!baseline.getId().equals(observation.getBaselineId())
                    || !baseline.getUnit().equals(observation.getUnit())) {
                throw new IllegalStateException(
                        "Daily observation baseline mismatch"
                );
            }
        }

        LocalDate today =
                LocalDate.now(ZoneId.of(market.getTimezone()));

        LocalDate maxDeadline = pending.stream()
                .map(MarketObservation::getObservationDeadlineDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        if (maxDeadline == null) {
            return;
        }

        LocalDate end = today.isBefore(maxDeadline)
                ? today
                : maxDeadline;

        LocalDate start =
                baseline.getBaselineSessionDate().plusDays(1);

        List<LocalDate> published = new ArrayList<>();
        boolean unresolved = false;

        for (LocalDate date = start;
             !date.isAfter(end);
             date = date.plusDays(1)) {

            if (!isFinalized(market, date)) {
                unresolved = true;
                break;
            }

            var result = marketDataService.regularCloseOn(
                    market,
                    date
            );

            if (result.status() == MarketDataProvider.Status.ready) {
                published.add(date);

                if (published.size() >= 20) {
                    break;
                }
            } else if (result.status()
                    == MarketDataProvider.Status.pending
                    || !missing(result)) {

                unresolved = true;
                break;
            }
        }

        for (MarketObservation observation : pending) {
            LocalDate expected =
                    observation.getExpectedObservationDate();

            LocalDate deadline =
                    observation.getObservationDeadlineDate();

            if (expected == null
                    || deadline == null
                    || deadline.isBefore(expected)) {
                continue;
            }

            int horizon = observation.getHorizon();

            if (published.size() >= horizon) {
                LocalDate actual = published.get(horizon - 1);

                if (!actual.isAfter(deadline)) {
                    int delay = Math.max(
                            0,
                            weekdaysBetween(expected, actual)
                    );

                    var status = collectionService.collectObservation(
                            sessionId,
                            targetId,
                            horizon,
                            actual,
                            delay
                    );

                    if (status == MarketDataProvider.Status.ready) {
                        log.info(
                                "Daily observation ready: target={}, horizon={}, date={}",
                                targetId,
                                horizon,
                                actual
                        );
                    }

                    continue;
                }
            }

            if (!unresolved && today.isAfter(deadline)) {
                collectionService.expireObservation(
                        sessionId,
                        targetId,
                        horizon,
                        MarketObservation.OutcomeExceptionStatus.data_missing,
                        "OBSERVATION_DEADLINE_EXCEEDED"
                );
            }
        }
    }

    private boolean isFinalized(
            MarketTarget market,
            LocalDate date
    ) {
        return !OffsetDateTime.now().isBefore(
                referenceAt(market, date)
                        .plus(confirmationDelay)
        );
    }

    private boolean missing(
            MarketDataService.Result<MarketDataService.Close> result
    ) {
        return result.status()
                == MarketDataProvider.Status.unavailable
                && result.diagnostic() != null
                && result.diagnostic()
                .contains("missing_requested_observation");
    }

    private LocalDate firstDate(
            MarketTarget market,
            OffsetDateTime submittedAt
    ) {
        LocalDate date = submittedAt
                .atZoneSameInstant(ZoneId.of(market.getTimezone()))
                .toLocalDate();

        while (!isWeekday(date)
                || !referenceAt(market, date).isAfter(submittedAt)) {
            date = date.plusDays(1);
        }

        return date;
    }

    private OffsetDateTime referenceAt(
            MarketTarget market,
            LocalDate date
    ) {
        ZoneId zone = ZoneId.of(market.getTimezone());

        if ("USTY".equals(market.getCalendarCode())) {
            return date.atTime(15, 30)
                    .atZone(zone)
                    .toOffsetDateTime();
        }

        return date.plusDays(1)
                .atStartOfDay(zone)
                .toOffsetDateTime();
    }

    private static LocalDate addWeekdays(
            LocalDate start,
            int count
    ) {
        LocalDate date = start;

        while (count > 0) {
            date = date.plusDays(1);

            if (isWeekday(date)) {
                count--;
            }
        }

        return date;
    }

    private static int weekdaysBetween(
            LocalDate start,
            LocalDate end
    ) {
        int count = 0;

        for (LocalDate date = start.plusDays(1);
             !date.isAfter(end);
             date = date.plusDays(1)) {

            if (isWeekday(date)) {
                count++;
            }
        }

        return count;
    }

    private static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
