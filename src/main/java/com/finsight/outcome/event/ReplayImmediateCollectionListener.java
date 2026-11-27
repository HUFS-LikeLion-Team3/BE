package com.finsight.outcome.event;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.service.DailyOutcomeScheduler;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Replay의 기준값과 T1·T5·T20은 이미 확정된 과거 값이므로, 제출이 커밋되면
 * 정기 수집을 기다리지 않고 그 세션만 바로 수집한다. 실패하면 정기 수집이 이어받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "finsight.outcome.collection.enabled",
        havingValue = "true"
)
public class ReplayImmediateCollectionListener {

    private final LearningSessionRepository sessionRepository;
    private final DailyOutcomeScheduler dailyOutcomeScheduler;

    @Async("outcomeTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePredictionSubmitted(PredictionSubmittedEvent event) {
        UUID sessionId = event.learningSessionId();
        boolean replay = sessionRepository.findById(sessionId)
                .map(session -> session.getSessionType() == LearningSession.SessionType.replay)
                .orElse(false);
        if (!replay) {
            return;
        }
        try {
            dailyOutcomeScheduler.collectNow(sessionId);
        } catch (RuntimeException e) {
            log.warn("Immediate replay collection failed: sessionId={}, type={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }
}
