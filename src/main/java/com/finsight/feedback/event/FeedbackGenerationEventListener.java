package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackGenerationService;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class FeedbackGenerationEventListener {

    private final FeedbackGenerationService feedbackGenerationService;

    @Value("${finsight.ai.enabled:true}")
    private boolean enabled;

    @Async("feedbackTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePredictionSubmitted(PredictionSubmittedEvent event) {
        if (!enabled) {
            return;
        }

        feedbackGenerationService.generateInitial(
                event.learningSessionId(),
                event.submittedAt()
        );
    }

    @Async("feedbackTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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