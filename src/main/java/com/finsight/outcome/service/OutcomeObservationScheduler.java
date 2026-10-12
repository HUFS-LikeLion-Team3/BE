package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
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

import java.time.LocalDate;
import java.util.ArrayList;
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
public class OutcomeObservationScheduler {

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
    private final OutcomeScheduleService scheduleService;
    private final OutcomeCollectionService collectionService;

    @Value("${finsight.outcome.collection.page-size:100}")
    private int pageSize = 100;

    @Scheduled(
            fixedDelayString =
                    "${finsight.outcome.collection.fixed-delay-ms:3600000}",
            initialDelayString =
                    "${finsight.outcome.collection.observation-initial-delay-ms:60000}"
    )
    public void initializeObservationSchedules() {
        List<UUID> sessionIds;

        try {
            sessionIds = findCandidateSessionIds();
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to find observation schedule candidates",
                    exception
            );
            return;
        }

        for (UUID sessionId : sessionIds) {
            initializeSession(sessionId);
        }
    }

    private List<UUID> findCandidateSessionIds() {
        List<UUID> sessionIds = new ArrayList<>();

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

                sessionIds.add(session.getId());
            }

            pageNumber++;
        } while (page.hasNext());

        return sessionIds;
    }

    private void initializeSession(UUID sessionId) {
        try {
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
                    initializeTarget(sessionId, selected);
                } catch (MarketDataProvider.DataException exception) {
                    log.debug(
                            "Observation calendar unavailable: sessionTargetId={}, status={}",
                            selected.getId(),
                            exception.status()
                    );
                } catch (RuntimeException exception) {
                    log.warn(
                            "Observation initialization failed: sessionTargetId={}, errorType={}",
                            selected.getId(),
                            exception.getClass().getSimpleName()
                    );
                }
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Observation preparation failed: sessionId={}, errorType={}",
                    sessionId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void initializeTarget(
            UUID sessionId,
            SessionTarget selected
    ) {
        UUID sessionTargetId = selected.getId();

        SessionTargetBaseline baseline =
                baselineRepository
                        .findBySessionTargetId(sessionTargetId)
                        .orElse(null);

        if (baseline == null
                || baseline.getStatus()
                != SessionTargetBaseline.BaselineStatus.ready
                || baseline.getBaselineSessionDate() == null
                || baseline.getBaselineAt() == null) {
            return;
        }

        boolean schedulesExist = observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(sessionTargetId)
                .stream()
                .map(observation -> observation.getHorizon())
                .filter(HORIZONS::contains)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(HORIZONS);

        if (schedulesExist) {
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

        Map<Integer, LocalDate> expectedDates =
                scheduleService.calculateObservationDates(
                        target,
                        baseline.getBaselineSessionDate()
                );

        Map<Integer, LocalDate> deadlineDates =
                scheduleService.calculateObservationDeadlines(
                        target,
                        expectedDates
                );

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                expectedDates,
                deadlineDates
        );

        log.info(
                "Observation schedules initialized: sessionTargetId={}",
                sessionTargetId
        );
    }
}