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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class OutcomeObservationCollectionScheduler {

    private static final List<LearningSession.LearningStatus>
            ACTIVE_STATUSES = List.of(
            LearningSession.LearningStatus.baseline_pending,
            LearningSession.LearningStatus.observing
    );

    private static final Set<Integer> HORIZONS =
            Set.of(1, 5, 20);

    private static final Set<String> DAILY_MARKET_CODES =
            Set.of("USTY", "TDFX", "TDGD", "TDWT");

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final MarketTargetRepository marketTargetRepository;
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
                    "${finsight.outcome.collection.fixed-delay-ms:3600000}",
            initialDelayString =
                    "${finsight.outcome.collection.result-initial-delay-ms:90000}"
    )
    public void collectObservations() {
        List<UUID> sessionIds;

        try {
            sessionIds = findCandidateSessionIds();
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to find observation collection candidates",
                    exception
            );
            return;
        }

        for (UUID sessionId : sessionIds) {
            try {
                collectSession(sessionId);
            } catch (RuntimeException exception) {
                log.warn(
                        "Observation collection failed: sessionId={}, errorType={}",
                        sessionId,
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    private List<UUID> findCandidateSessionIds() {
        List<UUID> result = new ArrayList<>();

        int pageNumber = 0;
        int size = Math.max(1, pageSize);

        Page<LearningSession> page;

        do {
            page = sessionRepository.findAllByStatusIn(
                    ACTIVE_STATUSES,
                    PageRequest.of(
                            pageNumber,
                            size,
                            Sort.by(Sort.Direction.ASC, "id")
                    )
            );

            for (LearningSession session : page.getContent()) {
                if (session.getSessionType()
                        != LearningSession.SessionType.live) {
                    continue;
                }

                if (session.getSubmittedAt() == null) {
                    continue;
                }

                result.add(session.getId());
            }

            pageNumber++;
        } while (page.hasNext());

        return result;
    }

    private void collectSession(UUID sessionId) {
        LearningSession session = sessionRepository.findById(sessionId)
                .orElse(null);

        if (session == null
                || session.getSubmittedAt() == null
                || session.getSessionType()
                != LearningSession.SessionType.live
                || (session.getStatus()
                != LearningSession.LearningStatus.baseline_pending
                && session.getStatus()
                != LearningSession.LearningStatus.observing)) {
            return;
        }

        List<SessionTarget> selectedTargets =
                sessionTargetRepository
                        .findAllByLearningSessionId(sessionId)
                        .stream()
                        .filter(SessionTarget::isSelected)
                        .toList();

        for (SessionTarget selected : selectedTargets) {
            try {
                collectTarget(sessionId, selected);
            } catch (MarketDataProvider.DataException exception) {
                log.debug(
                        "Market data unavailable during observation collection: targetId={}, status={}",
                        selected.getId(),
                        exception.status()
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Target observation collection failed: targetId={}, errorType={}",
                        selected.getId(),
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    private void collectTarget(
            UUID sessionId,
            SessionTarget selected
    ) {
        UUID sessionTargetId = selected.getId();

        SessionTargetBaseline baseline = baselineRepository
                .findBySessionTargetId(sessionTargetId)
                .orElse(null);

        if (baseline == null
                || baseline.getStatus()
                != SessionTargetBaseline.BaselineStatus.ready) {
            return;
        }

        MarketTarget target = marketTargetRepository
                .findById(selected.getMarketTargetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Market target not found"
                ));

        if (DAILY_MARKET_CODES.contains(target.getCalendarCode())) {
            return;
        }

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        for (MarketObservation observation : observations) {
            if (!HORIZONS.contains(observation.getHorizon())) {
                continue;
            }

            if (observation.getStatus()
                    != MarketObservation.OutcomeStatus.pending) {
                continue;
            }

            collectObservation(
                    sessionId,
                    sessionTargetId,
                    target,
                    baseline,
                    observation
            );
        }
    }

    private void collectObservation(
            UUID sessionId,
            UUID sessionTargetId,
            MarketTarget target,
            SessionTargetBaseline baseline,
            MarketObservation observation
    ) {
        LocalDate expectedDate =
                observation.getExpectedObservationDate();

        LocalDate deadlineDate =
                observation.getObservationDeadlineDate();

        if (expectedDate == null
                || deadlineDate == null
                || deadlineDate.isBefore(expectedDate)) {
            return;
        }

        if (!baseline.getId().equals(observation.getBaselineId())
                || !baseline.getUnit().equals(observation.getUnit())) {
            throw new IllegalStateException(
                    "Observation baseline mismatch"
            );
        }

        ZoneId zone = ZoneId.of(target.getTimezone());
        LocalDate today = LocalDate.now(zone);

        if (today.isBefore(expectedDate)) {
            return;
        }

        LocalDate lastCheckDate =
                today.isBefore(deadlineDate)
                        ? today
                        : deadlineDate;

        int delaySessions = 0;

        for (LocalDate date = expectedDate;
             !date.isAfter(lastCheckDate);
             date = date.plusDays(1)) {

            MarketDataProvider.Session marketSession =
                    marketDataService.regularSessionOn(
                            target,
                            date
                    );

            if (marketSession.isClosed()) {
                continue;
            }

            Instant closeAt = resolveCloseInstant(
                    marketSession,
                    date,
                    zone
            );

            if (date.isAfter(expectedDate)) {
                delaySessions++;
            }

            if (Instant.now().isBefore(
                    closeAt.plus(confirmationDelay))) {
                return;
            }

            MarketDataProvider.Status result =
                    collectionService.collectObservation(
                            sessionId,
                            sessionTargetId,
                            observation.getHorizon(),
                            date,
                            delaySessions
                    );

            if (result == MarketDataProvider.Status.ready) {
                log.info(
                        "Observation ready: targetId={}, horizon={}, date={}, delaySessions={}",
                        sessionTargetId,
                        observation.getHorizon(),
                        date,
                        delaySessions
                );
                return;
            }

            if (result == MarketDataProvider.Status.pending) {
                return;
            }

            // unavailable인 날짜는 확정 관측값이 없는 상태.
            // 다음 정규 거래 세션을 확인한다.
        }

        // 마감일 당일에는 종가 확정 이후에도 곧바로 종료하지 않음.
        // 다음 현지 날짜에 모든 후보 날짜를 재확인한 뒤 종료.
        if (today.isAfter(deadlineDate)) {
            collectionService.expireObservation(
                    sessionId,
                    sessionTargetId,
                    observation.getHorizon(),
                    MarketObservation.OutcomeExceptionStatus.data_missing,
                    "OBSERVATION_DEADLINE_EXCEEDED"
            );

            log.info(
                    "Observation expired: targetId={}, horizon={}",
                    sessionTargetId,
                    observation.getHorizon()
            );
        }
    }

    private Instant resolveCloseInstant(
            MarketDataProvider.Session session,
            LocalDate date,
            ZoneId zone
    ) {
        if (session == null
                || !date.equals(session.date())
                || !zone.equals(session.timezone())
                || session.open() == null
                || session.close() == null
                || !session.open().isBefore(session.close())) {

            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.unavailable,
                    "invalid_market_session"
            );
        }

        LocalDateTime localClose =
                LocalDateTime.of(
                        session.date(),
                        session.close()
                );

        var offsets = zone.getRules()
                .getValidOffsets(localClose);

        if (offsets.size() != 1) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.unavailable,
                    "ambiguous_market_close"
            );
        }

        return localClose
                .atOffset(offsets.getFirst())
                .toInstant();
    }
}