
package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.OutcomeSeriesPoint;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class OutcomeSeriesBackfillScheduler {

    private static final int MAX_DAYS = 180;

    private static final List<LearningSession.LearningStatus> STATUSES =
            List.of(
                    LearningSession.LearningStatus.baseline_pending,
                    LearningSession.LearningStatus.observing,
                    LearningSession.LearningStatus.reflection_pending,
                    LearningSession.LearningStatus.completed
            );

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository targetRepository;
    private final MarketTargetRepository marketRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final OutcomeSeriesPointRepository seriesRepository;
    private final MarketDataService marketDataService;
    private final PlatformTransactionManager transactionManager;

    @Value("${finsight.outcome.collection.page-size:100}")
    private int pageSize = 100;

    @Scheduled(
            fixedDelayString =
                    "${finsight.outcome.collection.series-delay-ms:86400000}",
            initialDelayString =
                    "${finsight.outcome.collection.series-initial-delay-ms:180000}"
    )
    public void backfill() {
        int pageNumber = 0;
        Page<LearningSession> page;

        do {
            try {
                page = sessionRepository.findAllByStatusIn(
                        STATUSES,
                        PageRequest.of(
                                pageNumber++,
                                Math.max(1, pageSize),
                                Sort.by("id")
                        )
                );
            } catch (RuntimeException e) {
                log.error("Series candidate lookup failed", e);
                return;
            }

            for (LearningSession session : page.getContent()) {
                if (session.getSessionType() != LearningSession.SessionType.live
                        || session.getSubmittedAt() == null) {
                    continue;
                }

                for (SessionTarget target :
                        targetRepository.findAllByLearningSessionId(session.getId())) {
                    if (!target.isSelected()) {
                        continue;
                    }

                    try {
                        backfillTarget(target);
                    } catch (RuntimeException e) {
                        log.warn(
                                "Series backfill failed: targetId={}, type={}",
                                target.getId(),
                                e.getClass().getSimpleName()
                        );
                    }
                }
            }
        } while (page.hasNext());
    }

    private void backfillTarget(SessionTarget target) {
        UUID targetId = target.getId();

        SessionTargetBaseline baseline = baselineRepository
                .findBySessionTargetId(targetId)
                .orElse(null);

        if (baseline == null
                || baseline.getStatus() != SessionTargetBaseline.BaselineStatus.ready
                || baseline.getBaselineSessionDate() == null) {
            return;
        }

        var market = marketRepository
                .findById(target.getMarketTargetId())
                .orElse(null);

        if (market == null) {
            return;
        }

        LocalDate start = baseline.getBaselineSessionDate();
        LocalDate end = LocalDate.now(ZoneId.of(market.getTimezone()))
                .minusDays(1);

        end = end.isAfter(start.plusDays(MAX_DAYS))
                ? start.plusDays(MAX_DAYS)
                : end;

        MarketObservation t20 = observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(targetId)
                .stream()
                .filter(o -> o.getHorizon() == 20)
                .findFirst()
                .orElse(null);

        if (t20 != null) {
            LocalDate limit = null;

            if (t20.getStatus() == MarketObservation.OutcomeStatus.ready) {
                limit = t20.getObservationSessionDate();
            } else if (t20.getStatus()
                    == MarketObservation.OutcomeStatus.unavailable) {
                limit = t20.getObservationDeadlineDate();
            }

            if (limit != null && end.isAfter(limit)) {
                end = limit;
            }
        }

        for (LocalDate date = start;
             !date.isAfter(end);
             date = date.plusDays(1)) {

            if (seriesRepository
                    .findBySessionTargetIdAndTradingDate(targetId, date)
                    .isPresent()) {
                continue;
            }

            MarketDataService.Result<MarketDataService.Close> result =
                    marketDataService.regularCloseOn(market, date);

            if (result.status() == MarketDataProvider.Status.pending) {
                return;
            }

            if (result.status() != MarketDataProvider.Status.ready) {
                continue;
            }

            MarketDataService.Close close = result.data();

            if (close == null
                    || !date.equals(close.sessionDate())
                    || close.value() == null
                    || !baseline.getUnit().equals(close.unit())
                    || close.dataSourceName() == null) {
                throw new IllegalStateException(
                        "Invalid market series data"
                );
            }

            save(targetId, close);
        }
    }

    private void save(
            UUID targetId,
            MarketDataService.Close close
    ) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> {

                    if (seriesRepository
                            .findBySessionTargetIdAndTradingDate(
                                    targetId,
                                    close.sessionDate()
                            ).isPresent()) {
                        return;
                    }

                    seriesRepository.save(
                            OutcomeSeriesPoint.create(
                                    targetId,
                                    close.sessionDate(),
                                    close.value(),
                                    close.unit(),
                                    close.dataSourceName(),
                                    close.dataSourceUrl()
                            )
                    );
                });
    }
}
