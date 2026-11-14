
package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackSubmissionIntentService;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "finsight.ai",
        name = "enabled",
        havingValue = "true"
)
public class FeedbackSubmissionIntentListener {

    private final FeedbackSubmissionIntentService intentService;

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void onPredictionSubmitted(
            PredictionSubmittedEvent event
    ) {
        intentService.recordInitial(
                event.learningSessionId(),
                event.evidenceCutoffAt()
        );
    }

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT
    )
    public void onFinalReflectionSubmitted(
            FinalReflectionSubmittedEvent event
    ) {
        intentService.recordFinal(
                event.learningSessionId(),
                event.reflectionSubmissionId(),
                event.completedAt()
        );
    }
}
