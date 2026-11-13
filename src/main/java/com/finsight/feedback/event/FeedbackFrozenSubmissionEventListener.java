
package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackFrozenSubmissionCoordinator;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "finsight.ai",
        name = {
                "enabled",
                "freeze-on-submit.enabled"
        },
        havingValue = "true"
)
public class FeedbackFrozenSubmissionEventListener {

    private final FeedbackFrozenSubmissionCoordinator coordinator;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void onPredictionSubmitted(
            PredictionSubmittedEvent event
    ) {
        coordinator.freezeInitial(
                event.learningSessionId(),
                event.evidenceCutoffAt()
        );
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void onFinalReflectionSubmitted(
            FinalReflectionSubmittedEvent event
    ) {
        coordinator.freezeFinal(
                event.learningSessionId(),
                event.reflectionSubmissionId(),
                event.completedAt()
        );
    }
}
