
package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionStatusService;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.OutcomeCollectionEvent;
import com.finsight.outcome.entity.OutcomeSeriesPoint;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.OutcomeCollectionEventRepository;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutcomeCollectionService {

    private static final List<Integer> HORIZONS = List.of(1, 5, 20);

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final MarketTargetRepository marketTargetRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final OutcomeSeriesPointRepository seriesPointRepository;
    private final OutcomeCollectionEventRepository eventRepository;
    private final MarketDataService marketDataService;
    private final LearningSessionStatusService statusService;
    private final TransactionTemplate transactionTemplate;

    public OutcomeCollectionService(
            LearningSessionRepository sessionRepository,
            SessionTargetRepository sessionTargetRepository,
            MarketTargetRepository marketTargetRepository,
            SessionTargetBaselineRepository baselineRepository,
            MarketObservationRepository observationRepository,
            MarketDataService marketDataService,
            LearningSessionStatusService statusService,
            PlatformTransactionManager transactionManager,
            OutcomeSeriesPointRepository seriesPointRepository,
            OutcomeCollectionEventRepository eventRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.sessionTargetRepository = sessionTargetRepository;
        this.marketTargetRepository = marketTargetRepository;
        this.baselineRepository = baselineRepository;
        this.observationRepository = observationRepository;
        this.marketDataService = marketDataService;
        this.statusService = statusService;
        this.seriesPointRepository = seriesPointRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = new TransactionTemplate(
                transactionManager
        );
    }

    public void initializeBaseline(
            UUID sessionId,
            UUID sessionTargetId,
            LocalDate expectedDate,
            LocalDate deadlineDate
    ) {
        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);

            if (session.getStatus()
                    != LearningSession.LearningStatus.baseline_pending) {
                throw new IllegalStateException(
                        "Session is not waiting for baseline"
                );
            }

            SessionTarget selected =
                    requireSelectedTarget(sessionId, sessionTargetId);

            MarketTarget target = requireMarketTarget(selected);

            if (baselineRepository.findBySessionTargetId(
                    sessionTargetId
            ).isPresent()) {
                return;
            }

            SessionTargetBaseline baseline =
                    SessionTargetBaseline.createPending(
                            sessionTargetId,
                            expectedDate,
                            deadlineDate,
                            target.getUnit()
                    );

            baselineRepository.save(baseline);
        });
    }

    public MarketDataProvider.Status collectBaseline(
            UUID sessionId,
            UUID sessionTargetId,
            LocalDate date
    ) {
        SessionTarget selected =
                requireSelectedTarget(sessionId, sessionTargetId);

        MarketTarget target = requireMarketTarget(selected);

        MarketDataService.Result<MarketDataService.Close> result =
                marketDataService.regularCloseOn(target, date);

        if (result.status() != MarketDataProvider.Status.ready) {
            return result.status();
        }

        MarketDataService.Close close = result.data();

        if (close == null || !date.equals(close.sessionDate())) {
            throw new IllegalStateException(
                    "Market provider returned mismatched baseline date"
            );
        }

        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);
            requireSelectedTarget(sessionId, sessionTargetId);

            SessionTargetBaseline baseline =
                    requireBaseline(sessionTargetId);

            if (baseline.getStatus()
                    != SessionTargetBaseline.BaselineStatus.pending) {
                return;
            }

            if (!baseline.getUnit().equals(close.unit())) {
                throw new IllegalStateException(
                        "Baseline unit mismatch"
                );
            }

            if (close.asOfAt() == null
                    || close.asOfAt().isBefore(
                    session.getSubmittedAt())) {
                throw new IllegalStateException(
                        "Baseline must occur after submission"
                );
            }

            baseline.markReady(
                    close.sessionDate(),
                    close.asOfAt(),
                    close.value(),
                    close.dataSourceName(),
                    close.dataSourceUrl()
            );

            baselineRepository.save(baseline);

            saveSeriesPoint(
                    sessionTargetId,
                    close
            );

            statusService.reconcile(sessionId);
        });

        return MarketDataProvider.Status.ready;
    }

    public void expireBaseline(
            UUID sessionId,
            UUID sessionTargetId,
            String reason
    ) {
        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);
            requireSelectedTarget(sessionId, sessionTargetId);

            SessionTargetBaseline baseline =
                    requireBaseline(sessionTargetId);

            if (baseline.getStatus()
                    != SessionTargetBaseline.BaselineStatus.pending) {
                return;
            }

            baseline.markUnavailable(reason);
            baselineRepository.save(baseline);

            for (int horizon : HORIZONS) {
                MarketObservation observation =
                        findObservation(sessionTargetId, horizon);

                if (observation == null) {
                    observation = MarketObservation.createPending(
                            sessionTargetId,
                            baseline.getId(),
                            horizon,
                            session.getSubmittedAt(),
                            null,
                            null,
                            baseline.getUnit()
                    );
                }

                if (observation.getStatus()
                        == MarketObservation.OutcomeStatus.pending) {
                    observation.markUnavailable(
                            MarketObservation.OutcomeExceptionStatus.data_missing,
                            reason
                    );
                }

                observationRepository.save(observation);
            }

            saveCollectionEvent(
                    sessionTargetId,
                    null,
                    OutcomeCollectionEvent.OutcomeCollectionEventType.data_missing,
                    reason,
                    baseline.getDataSourceName(),
                    baseline.getDataSourceUrl()
            );

            statusService.reconcile(sessionId);
        });
    }

    public void initializeObservations(
            UUID sessionId,
            UUID sessionTargetId,
            Map<Integer, LocalDate> expectedDates,
            Map<Integer, LocalDate> deadlineDates
    ) {
        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);
            requireSelectedTarget(sessionId, sessionTargetId);

            SessionTargetBaseline baseline =
                    requireBaseline(sessionTargetId);

            if (baseline.getStatus()
                    != SessionTargetBaseline.BaselineStatus.ready) {
                throw new IllegalStateException(
                        "Baseline is not ready"
                );
            }

            for (int horizon : HORIZONS) {
                LocalDate expected = expectedDates.get(horizon);
                LocalDate deadline = deadlineDates.get(horizon);

                if (expected == null
                        || deadline == null
                        || deadline.isBefore(expected)
                        || !expected.isAfter(
                        baseline.getBaselineSessionDate())) {
                    throw new IllegalArgumentException(
                            "Invalid observation schedule: T" + horizon
                    );
                }
            }

            for (int horizon : HORIZONS) {
                if (findObservation(sessionTargetId, horizon) != null) {
                    continue;
                }

                MarketObservation observation =
                        MarketObservation.createPending(
                                sessionTargetId,
                                baseline.getId(),
                                horizon,
                                baseline.getBaselineAt(),
                                expectedDates.get(horizon),
                                deadlineDates.get(horizon),
                                baseline.getUnit()
                        );

                observationRepository.save(observation);
            }

            statusService.reconcile(sessionId);
        });
    }

    public MarketDataProvider.Status collectObservation(
            UUID sessionId,
            UUID sessionTargetId,
            int horizon,
            LocalDate date,
            int delaySessions
    ) {
        validateHorizon(horizon);

        SessionTarget selected =
                requireSelectedTarget(sessionId, sessionTargetId);

        MarketTarget target = requireMarketTarget(selected);

        MarketDataService.Result<MarketDataService.Close> result =
                marketDataService.regularCloseOn(target, date);

        if (result.status() != MarketDataProvider.Status.ready) {
            return result.status();
        }

        MarketDataService.Close close = result.data();

        if (close == null || !date.equals(close.sessionDate())) {
            throw new IllegalStateException(
                    "Market provider returned mismatched observation date"
            );
        }

        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);
            requireSelectedTarget(sessionId, sessionTargetId);

            SessionTargetBaseline baseline =
                    requireBaseline(sessionTargetId);

            if (baseline.getStatus()
                    != SessionTargetBaseline.BaselineStatus.ready) {
                throw new IllegalStateException(
                        "Baseline is not ready"
                );
            }

            MarketObservation observation =
                    findObservation(sessionTargetId, horizon);

            if (observation == null) {
                throw new IllegalStateException(
                        "Observation has not been initialized"
                );
            }

            if (observation.getStatus()
                    != MarketObservation.OutcomeStatus.pending) {
                return;
            }

            if (!observation.getBaselineId().equals(baseline.getId())
                    || !baseline.getUnit().equals(close.unit())) {
                throw new IllegalStateException(
                        "Observation baseline or unit mismatch"
                );
            }

            observation.markReady(
                    close.sessionDate(),
                    close.asOfAt(),
                    close.value(),
                    baseline.getBaselineValue(),
                    delaySessions,
                    close.dataSourceName(),
                    close.dataSourceUrl()
            );

            observationRepository.save(observation);

            saveSeriesPoint(
                    sessionTargetId,
                    close
            );

            statusService.reconcile(sessionId);
        });

        return MarketDataProvider.Status.ready;
    }

    public void expireObservation(
            UUID sessionId,
            UUID sessionTargetId,
            int horizon,
            MarketObservation.OutcomeExceptionStatus exceptionStatus,
            String reason
    ) {
        validateHorizon(horizon);

        transactionTemplate.executeWithoutResult(tx -> {
            LearningSession session = lockSession(sessionId);
            requireActiveSession(session);
            requireSelectedTarget(sessionId, sessionTargetId);

            MarketObservation observation =
                    findObservation(sessionTargetId, horizon);

            if (observation == null) {
                throw new IllegalStateException(
                        "Observation has not been initialized"
                );
            }

            if (observation.getStatus()
                    != MarketObservation.OutcomeStatus.pending) {
                return;
            }

            observation.markUnavailable(
                    exceptionStatus,
                    reason
            );

            observationRepository.save(observation);

            saveCollectionEvent(
                    sessionTargetId,
                    horizon,
                    toEventType(exceptionStatus),
                    reason,
                    observation.getDataSourceName(),
                    observation.getDataSourceUrl()
            );

            statusService.reconcile(sessionId);
        });
    }

    private void saveSeriesPoint(
            UUID sessionTargetId,
            MarketDataService.Close close
    ) {
        OutcomeSeriesPoint existing = seriesPointRepository
                .findBySessionTargetIdAndTradingDate(
                        sessionTargetId,
                        close.sessionDate()
                )
                .orElse(null);

        if (existing != null) {
            BigDecimal existingValue = existing.getCloseValue();
            BigDecimal newValue = close.value();

            if (existingValue == null
                    || newValue == null
                    || existingValue.compareTo(newValue) != 0
                    || !existing.getUnit().equals(close.unit())) {
                throw new IllegalStateException(
                        "Conflicting market series point: "
                                + sessionTargetId
                                + ", date=" + close.sessionDate()
                );
            }

            return;
        }

        OutcomeSeriesPoint point = OutcomeSeriesPoint.create(
                sessionTargetId,
                close.sessionDate(),
                close.value(),
                close.unit(),
                close.dataSourceName(),
                close.dataSourceUrl()
        );

        seriesPointRepository.save(point);
    }

    private void saveCollectionEvent(
            UUID sessionTargetId,
            Integer horizon,
            OutcomeCollectionEvent.OutcomeCollectionEventType eventType,
            String detail,
            String dataSourceName,
            String dataSourceUrl
    ) {
        OutcomeCollectionEvent event =
                OutcomeCollectionEvent.create(
                        sessionTargetId,
                        horizon,
                        eventType,
                        OffsetDateTime.now(),
                        detail,
                        dataSourceName,
                        dataSourceUrl
                );

        eventRepository.save(event);
    }

    private OutcomeCollectionEvent.OutcomeCollectionEventType toEventType(
            MarketObservation.OutcomeExceptionStatus exceptionStatus
    ) {
        if (exceptionStatus == null) {
            throw new IllegalArgumentException(
                    "Observation exception status is required"
            );
        }

        return switch (exceptionStatus) {
            case market_holiday ->
                    OutcomeCollectionEvent.OutcomeCollectionEventType.market_holiday;

            case trading_halt, long_term_halt ->
                    OutcomeCollectionEvent.OutcomeCollectionEventType.trading_halt;

            case data_missing ->
                    OutcomeCollectionEvent.OutcomeCollectionEventType.data_missing;

            case delisted, none ->
                    OutcomeCollectionEvent.OutcomeCollectionEventType.unavailable;
        };
    }

    private LearningSession lockSession(UUID sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Learning session not found: " + sessionId
                ));
    }

    private void requireActiveSession(LearningSession session) {
        if (session.getSubmittedAt() == null
                || session.getSessionType()
                != LearningSession.SessionType.live
                || (session.getStatus()
                != LearningSession.LearningStatus.baseline_pending
                && session.getStatus()
                != LearningSession.LearningStatus.observing)) {
            throw new IllegalStateException(
                    "Session is not eligible for market collection"
            );
        }
    }

    private SessionTarget requireSelectedTarget(
            UUID sessionId,
            UUID sessionTargetId
    ) {
        SessionTarget target = sessionTargetRepository
                .findById(sessionTargetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Session target not found: " + sessionTargetId
                ));

        if (!target.getLearningSessionId().equals(sessionId)
                || !target.isSelected()) {
            throw new IllegalArgumentException(
                    "Target is not selected in this session"
            );
        }

        return target;
    }

    private MarketTarget requireMarketTarget(
            SessionTarget selected
    ) {
        return marketTargetRepository
                .findById(selected.getMarketTargetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Market target not found"
                ));
    }

    private SessionTargetBaseline requireBaseline(
            UUID sessionTargetId
    ) {
        return baselineRepository
                .findBySessionTargetId(sessionTargetId)
                .orElseThrow(() -> new IllegalStateException(
                        "Baseline has not been initialized"
                ));
    }

    private MarketObservation findObservation(
            UUID sessionTargetId,
            int horizon
    ) {
        return observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(
                        sessionTargetId
                )
                .stream()
                .filter(value -> value.getHorizon() == horizon)
                .findFirst()
                .orElse(null);
    }

    private void validateHorizon(int horizon) {
        if (!HORIZONS.contains(horizon)) {
            throw new IllegalArgumentException(
                    "Invalid observation horizon: " + horizon
            );
        }
    }
}
