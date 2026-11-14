
package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackGenerationService;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "finsight.ai.freeze-on-submit",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class FeedbackGenerationEventListener {

    private final FeedbackGenerationService feedbackGenerationService;

    @Value("${finsight.ai.enabled:false}")
    private boolean enabled;

    @Async("feedbackTaskExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handlePredictionSubmitted(
            PredictionSubmittedEvent event
    ) {
        if (!enabled) {
            return;
        }

        feedbackGenerationService.generateInitial(
                event.learningSessionId(),
                event.evidenceCutoffAt()
        );
    }

    @Async("feedbackTaskExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handleFinalReflectionSubmitted(
            FinalReflectionSubmittedEvent event
    ) {
        if (!enabled) {
            return;
        }

        feedbackGenerationService.generateFinal(
                event.learningSessionId(),
                event.reflectionSubmissionId(),
                event.completedAt()
        );
    }
}
