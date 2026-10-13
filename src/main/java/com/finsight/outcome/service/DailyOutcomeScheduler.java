
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    private static final int RETRY_PUBLISHED_SESSIONS = 10;

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
                    addWeekdays(expected, RETRY_PUBLISHED_SESSIONS)
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
                LocalDate estimated = addWeekdays(start, horizon);

                expected.put(horizon, estimated);

                deadlines.put(
                        horizon,
                        addWeekdays(
                                estimated,
                                RETRY_PUBLISHED_SESSIONS
                        )
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

        if (expected == null) {
            return;
        }

        LocalDate today =
                LocalDate.now(ZoneId.of(market.getTimezone()));

        if (today.isBefore(expected)) {
            return;
        }

        List<LocalDate> firstPublished =
                publishedDates(market, expected, today, 1);

        if (firstPublished.isEmpty()) {
            return;
        }

        LocalDate first = firstPublished.getFirst();

        MarketDataProvider.Status status =
                collectionService.collectBaseline(
                        sessionId,
                        targetId,
                        first
                );

        if (status == MarketDataProvider.Status.ready) {
            log.info(
                    "Daily baseline ready: target={}, date={}",
                    targetId,
                    first
            );
            return;
        }

        List<LocalDate> later = publishedDates(
                market,
                first.plusDays(1),
                today,
                RETRY_PUBLISHED_SESSIONS
        );

        if (later.size() >= RETRY_PUBLISHED_SESSIONS) {
            collectionService.expireBaseline(
                    sessionId,
                    targetId,
                    "BASELINE_10_PUBLISHED_SESSIONS_EXCEEDED"
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

        LocalDate start =
                baseline.getBaselineSessionDate().plusDays(1);

        if (today.isBefore(start)) {
            return;
        }

        int lastHorizon = pending.stream()
                .mapToInt(MarketObservation::getHorizon)
                .max()
                .orElse(20);

        List<LocalDate> published =
                publishedDates(market, start, today, lastHorizon);

        for (MarketObservation observation : pending) {
            LocalDate expected =
                    observation.getExpectedObservationDate();

            LocalDate estimatedDeadline =
                    observation.getObservationDeadlineDate();

            if (expected == null
                    || estimatedDeadline == null
                    || estimatedDeadline.isBefore(expected)) {
                continue;
            }

            int horizon = observation.getHorizon();

            if (published.size() < horizon) {
                continue;
            }

            LocalDate actual = published.get(horizon - 1);

            int delay = Math.max(
                    0,
                    weekdaysBetween(expected, actual)
            );

            MarketDataProvider.Status status =
                    collectionService.collectObservation(
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
                continue;
            }

            List<LocalDate> later = publishedDates(
                    market,
                    actual.plusDays(1),
                    today,
                    RETRY_PUBLISHED_SESSIONS
            );

            if (later.size() >= RETRY_PUBLISHED_SESSIONS) {
                collectionService.expireObservation(
                        sessionId,
                        targetId,
                        horizon,
                        MarketObservation.OutcomeExceptionStatus.data_missing,
                        "OBSERVATION_10_PUBLISHED_SESSIONS_EXCEEDED"
                );
            }
        }
    }

    private List<LocalDate> publishedDates(
            MarketTarget market,
            LocalDate start,
            LocalDate today,
            int maxResults
    ) {
        List<LocalDate> published = new ArrayList<>();

        for (LocalDate date = start;
             !date.isAfter(today) && published.size() < maxResults;
             date = date.plusDays(1)) {

            if (!isFinalized(market, date)) {
                break;
            }

            MarketDataService.Result<MarketDataService.Close> result =
                    marketDataService.regularCloseOn(market, date);

            if (result.status() == MarketDataProvider.Status.ready) {
                MarketDataService.Close close = result.data();

                if (close == null
                        || !date.equals(close.sessionDate())
                        || close.value() == null
                        || close.asOfAt() == null) {
                    throw new IllegalStateException(
                            "Invalid daily published value"
                    );
                }

                published.add(date);

            } else if (!missing(result)) {
                break;
            }
        }

        return published;
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
        return result.status() == MarketDataProvider.Status.unavailable
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
