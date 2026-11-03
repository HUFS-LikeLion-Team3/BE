package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionStatusService;
import com.finsight.market.data.*;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.*;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutcomeCollectionSubmissionBoundaryTest {
    @Test void rejectsBeforeAndEqualSubmissionButPersistsStrictlyLaterClose() {
        for (int offset : List.of(-1, 0, 1)) {
            var sessions = mock(LearningSessionRepository.class);
            var targets = mock(SessionTargetRepository.class);
            var markets = mock(MarketTargetRepository.class);
            var baselines = mock(SessionTargetBaselineRepository.class);
            var series = mock(OutcomeSeriesPointRepository.class);
            var data = mock(MarketDataService.class);
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            var service = new OutcomeCollectionService(sessions, targets, markets, baselines,
                    mock(MarketObservationRepository.class), data, mock(LearningSessionStatusService.class),
                    manager, series, mock(OutcomeCollectionEventRepository.class));
            UUID sessionId = UUID.randomUUID(), targetId = UUID.randomUUID(), marketId = UUID.randomUUID();
            var submitted = OffsetDateTime.parse("2026-10-09T16:00:00-04:00");
            var session = mock(LearningSession.class);
            when(session.getSubmittedAt()).thenReturn(submitted);
            when(session.getSessionType()).thenReturn(LearningSession.SessionType.live);
            when(session.getStatus()).thenReturn(LearningSession.LearningStatus.baseline_pending);
            when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
            var selected = SessionTarget.create(sessionId, marketId, 1);
            when(targets.findById(targetId)).thenReturn(Optional.of(selected));
            var market = mock(MarketTarget.class);
            when(markets.findById(marketId)).thenReturn(Optional.of(market));
            var baseline = mock(SessionTargetBaseline.class);
            when(baseline.getStatus()).thenReturn(SessionTargetBaseline.BaselineStatus.pending);
            when(baseline.getUnit()).thenReturn("USD");
            when(baselines.findBySessionTargetId(targetId)).thenReturn(Optional.of(baseline));
            var date = submitted.toLocalDate();
            var close = new MarketDataService.Close(date, submitted.plusSeconds(offset), BigDecimal.TEN, "USD", "Provider", null);
            when(data.regularCloseOn(market, date)).thenReturn(new MarketDataService.Result<>(MarketDataProvider.Status.ready, close));
            if (offset <= 0) {
                assertThrows(IllegalStateException.class, () -> service.collectBaseline(sessionId, targetId, date));
                verify(baselines, never()).save(any());
                verify(series, never()).save(any());
            } else {
                assertEquals(MarketDataProvider.Status.ready, service.collectBaseline(sessionId, targetId, date));
                verify(baseline).markReady(date, close.asOfAt(), BigDecimal.TEN, "Provider", null);
                verify(baselines).save(baseline);
            }
        }
    }
}
