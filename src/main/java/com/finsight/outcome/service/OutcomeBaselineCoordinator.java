
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class OutcomeBaselineCoordinator {

    private static final Set<String> DAILY_MARKET_CODES =
            Set.of("USTY", "TDFX", "TDGD", "TDWT");

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final MarketTargetRepository marketTargetRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketDataService marketDataService;
    private final OutcomeScheduleService scheduleService;
    private final OutcomeCollectionService collectionService;
    private final Duration confirmationDelay;
    private final Clock clock;

    public OutcomeBaselineCoordinator(
            LearningSessionRepository sessionRepository,
            SessionTargetRepository sessionTargetRepository,
            MarketTargetRepository marketTargetRepository,
            SessionTargetBaselineRepository baselineRepository,
            MarketDataService marketDataService,
            OutcomeScheduleService scheduleService,
            OutcomeCollectionService collectionService
    ) {
        this(
                sessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                marketDataService,
                scheduleService,
                collectionService,
                Duration.ofMinutes(15),
                Clock.systemUTC()
        );
    }

    @Autowired
    public OutcomeBaselineCoordinator(
            LearningSessionRepository sessionRepository,
            SessionTargetRepository sessionTargetRepository,
            MarketTargetRepository marketTargetRepository,
            SessionTargetBaselineRepository baselineRepository,
            MarketDataService marketDataService,
            OutcomeScheduleService scheduleService,
            OutcomeCollectionService collectionService,
            @Value("${market.data.confirmation-delay:PT15M}")
            Duration confirmationDelay
    ) {
        this(
                sessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                marketDataService,
                scheduleService,
                collectionService,
                confirmationDelay,
                Clock.systemUTC()
        );
    }

    OutcomeBaselineCoordinator(
            LearningSessionRepository sessionRepository,
            SessionTargetRepository sessionTargetRepository,
            MarketTargetRepository marketTargetRepository,
            SessionTargetBaselineRepository baselineRepository,
            MarketDataService marketDataService,
            OutcomeScheduleService scheduleService,
            OutcomeCollectionService collectionService,
            Duration confirmationDelay,
            Clock clock
    ) {
        if (confirmationDelay == null
                || confirmationDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "Invalid market confirmation delay"
            );
        }

        this.sessionRepository = sessionRepository;
        this.sessionTargetRepository = sessionTargetRepository;
        this.marketTargetRepository = marketTargetRepository;
        this.baselineRepository = baselineRepository;
        this.marketDataService = marketDataService;
        this.scheduleService = scheduleService;
        this.collectionService = collectionService;
        this.confirmationDelay = confirmationDelay;
        this.clock = Objects.requireNonNull(clock);
    }

    public enum AttemptStatus {
        READY,
        WAITING,
        RETRY_LATER,
        ALREADY_FINALIZED,
        UNSUPPORTED_DAILY_MARKET,
        PROVIDER_UNAVAILABLE,
        DEADLINE_PASSED_UNRESOLVED
    }

    public record BaselineAttempt(
            UUID sessionTargetId,
            AttemptStatus status,
            LocalDate checkedDate
    ) {
    }

    public List<BaselineAttempt> runOnce(UUID sessionId) {
        LearningSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Learning session not found: " + sessionId
                ));

        if (session.getSubmittedAt() == null
                || session.getSessionType()
                != LearningSession.SessionType.live
                || (session.getStatus()
                != LearningSession.LearningStatus.baseline_pending
                && session.getStatus()
                != LearningSession.LearningStatus.observing)) {
            throw new IllegalStateException(
                    "Session is not eligible for baseline collection"
            );
        }

        List<SessionTarget> selectedTargets =
                sessionTargetRepository
                        .findAllByLearningSessionId(sessionId)
                        .stream()
                        .filter(SessionTarget::isSelected)
                        .toList();

        List<BaselineAttempt> attempts = new ArrayList<>();

        for (SessionTarget selected : selectedTargets) {
            try {
                attempts.add(
                        collectTargetBaseline(
                                sessionId,
                                session,
                                selected
                        )
                );
            } catch (RuntimeException exception) {
                log.warn(
                        "Baseline collection failed: sessionId={}, sessionTargetId={}",
                        sessionId,
                        selected.getId(),
                        exception
                );

                attempts.add(new BaselineAttempt(
                        selected.getId(),
                        AttemptStatus.PROVIDER_UNAVAILABLE,
                        null
                ));
            }
        }

        return List.copyOf(attempts);
    }

    private BaselineAttempt collectTargetBaseline(
            UUID sessionId,
            LearningSession session,
            SessionTarget selected
    ) {
        UUID sessionTargetId = selected.getId();

        try {
            MarketTarget target = marketTargetRepository
                    .findById(selected.getMarketTargetId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Market target not found"
                    ));

            if (DAILY_MARKET_CODES.contains(target.getCalendarCode())) {
                return attempt(
                        sessionTargetId,
                        AttemptStatus.UNSUPPORTED_DAILY_MARKET,
                        null
                );
            }

            SessionTargetBaseline baseline = baselineRepository
                    .findBySessionTargetId(sessionTargetId)
                    .orElse(null);

            if (baseline == null) {
                OutcomeScheduleService.BaselineSchedule schedule =
                        scheduleService.calculateBaselineSchedule(
                                target,
                                session.getSubmittedAt()
                        );

                collectionService.initializeBaseline(
                        sessionId,
                        sessionTargetId,
                        schedule.expectedDate(),
                        schedule.deadlineDate()
                );

                baseline = baselineRepository
                        .findBySessionTargetId(sessionTargetId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Baseline initialization failed"
                        ));
            }

            if (baseline.getStatus()
                    != SessionTargetBaseline.BaselineStatus.pending) {
                return attempt(
                        sessionTargetId,
                        AttemptStatus.ALREADY_FINALIZED,
                        baseline.getBaselineSessionDate()
                );
            }

            LocalDate expectedDate =
                    baseline.getExpectedBaselineDate();

            LocalDate deadlineDate =
                    baseline.getBaselineDeadlineDate();

            if (expectedDate == null
                    || deadlineDate == null
                    || deadlineDate.isBefore(expectedDate)) {
                throw new IllegalStateException(
                        "Invalid baseline schedule: " + sessionTargetId
                );
            }

            ZoneId timezone = ZoneId.of(target.getTimezone());

            LocalDate today = clock.instant()
                    .atZone(timezone)
                    .toLocalDate();

            if (hasDeadlineElapsed(
                    target,
                    deadlineDate,
                    clock.instant()
            )) {
                return attempt(
                        sessionTargetId,
                        AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                        deadlineDate
                );
            }

            LocalDate lastCheckDate = today.isBefore(deadlineDate)
                    ? today
                    : deadlineDate;

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

                if (hasDeadlineElapsed(
                        target,
                        deadlineDate,
                        clock.instant()
                )) {
                    return attempt(
                            sessionTargetId,
                            AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                            deadlineDate
                    );
                }

                MarketDataProvider.Status result =
                        collectionService.collectBaseline(
                                sessionId,
                                sessionTargetId,
                                date
                        );

                if (result == MarketDataProvider.Status.ready) {
                    return attempt(
                            sessionTargetId,
                            AttemptStatus.READY,
                            date
                    );
                }

                if (result == MarketDataProvider.Status.pending) {
                    if (hasDeadlineElapsed(
                            target,
                            deadlineDate,
                            clock.instant()
                    )) {
                        return attempt(
                                sessionTargetId,
                                AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                                deadlineDate
                        );
                    }

                    return attempt(
                            sessionTargetId,
                            AttemptStatus.RETRY_LATER,
                            date
                    );
                }
            }

            if (hasDeadlineElapsed(
                    target,
                    deadlineDate,
                    clock.instant()
            )) {
                return attempt(
                        sessionTargetId,
                        AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                        deadlineDate
                );
            }

            return attempt(
                    sessionTargetId,
                    AttemptStatus.WAITING,
                    lastCheckDate
            );

        } catch (MarketDataProvider.DataException exception) {
            AttemptStatus status =
                    exception.status() == MarketDataProvider.Status.pending
                            ? AttemptStatus.RETRY_LATER
                            : AttemptStatus.PROVIDER_UNAVAILABLE;

            return attempt(sessionTargetId, status, null);
        }
    }

    private boolean hasDeadlineElapsed(
            MarketTarget target,
            LocalDate deadlineDate,
            Instant now
    ) {
        ZoneId timezone = ZoneId.of(target.getTimezone());
        LocalDate today = now.atZone(timezone).toLocalDate();

        if (today.isBefore(deadlineDate)) {
            return false;
        }

        if (today.isAfter(deadlineDate)) {
            return true;
        }

        MarketDataProvider.Session deadlineSession =
                marketDataService.regularSessionOn(
                        target,
                        deadlineDate
                );

        if (deadlineSession == null
                || deadlineSession.isClosed()
                || deadlineSession.close() == null
                || deadlineSession.timezone() == null
                || !deadlineDate.equals(deadlineSession.date())) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.pending,
                    "baseline_deadline_session_unresolved"
            );
        }

        LocalDateTime localClose =
                deadlineDate.atTime(deadlineSession.close());

        List<ZoneOffset> offsets =
                deadlineSession.timezone()
                        .getRules()
                        .getValidOffsets(localClose);

        if (offsets.size() != 1) {
            throw new MarketDataProvider.DataException(
                    MarketDataProvider.Status.pending,
                    "baseline_deadline_close_unresolved"
            );
        }

        Instant deadlineInstant = localClose
                .atOffset(offsets.getFirst())
                .toInstant()
                .plus(confirmationDelay);

        return !now.isBefore(deadlineInstant);
    }

    private BaselineAttempt attempt(
            UUID sessionTargetId,
            AttemptStatus status,
            LocalDate date
    ) {
        return new BaselineAttempt(sessionTargetId, status, date);
    }
}
