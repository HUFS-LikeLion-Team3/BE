
package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackFrozenDispatchGate;
import com.finsight.feedback.service.FeedbackGenerationService;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
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
public class FeedbackFrozenDispatchEventListener {

    private final FeedbackFrozenDispatchGate dispatchGate;
    private final FeedbackGenerationService generationService;

    @Async("feedbackTaskExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handlePredictionSubmitted(
            PredictionSubmittedEvent event
    ) {
        if (!dispatchGate.canDispatchInitial(
                event.learningSessionId())) {
            return;
        }

        // An existing frozen job must be present.
        // generateInitial() must reuse that saved job.
        generationService.generateInitial(
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
        if (!dispatchGate.canDispatchFinal(
                event.learningSessionId(),
                event.reflectionSubmissionId())) {
            return;
        }

        // FINAL must use its persisted input.
        // Do not resolve cutoff or rebuild FINAL input here.
        generationService.resumeFinal(
                event.learningSessionId()
        );
    }
}
