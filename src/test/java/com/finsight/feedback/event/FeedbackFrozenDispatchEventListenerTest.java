
package com.finsight.feedback.event;

import com.finsight.feedback.service.FeedbackFrozenDispatchGate;
import com.finsight.feedback.service.FeedbackGenerationService;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackFrozenDispatchEventListenerTest {

    @Mock
    FeedbackFrozenDispatchGate dispatchGate;

    @Mock
    FeedbackGenerationService generationService;

    @InjectMocks
    FeedbackFrozenDispatchEventListener listener;

    private final UUID sessionId = UUID.randomUUID();

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse(
                    "2026-11-05T10:00:00+09:00"
            );

    @Test
    void initialWithoutFrozenInputDoesNotGenerate() {
        when(dispatchGate.canDispatchInitial(sessionId))
                .thenReturn(false);

        listener.handlePredictionSubmitted(
                new PredictionSubmittedEvent(
                        sessionId,
                        UUID.randomUUID(),
                        submittedAt
                )
        );

        verifyNoInteractions(generationService);
    }

    @Test
    void frozenInitialContinuesExistingJob() {
        when(dispatchGate.canDispatchInitial(sessionId))
                .thenReturn(true);

        listener.handlePredictionSubmitted(
                new PredictionSubmittedEvent(
                        sessionId,
                        UUID.randomUUID(),
                        submittedAt
                )
        );

        verify(generationService).generateInitial(
                sessionId,
                submittedAt
        );

        verifyNoMoreInteractions(generationService);
    }

    @Test
    void finalWithoutFrozenInputDoesNotGenerate() {
        UUID submissionId = UUID.randomUUID();

        when(dispatchGate.canDispatchFinal(
                sessionId,
                submissionId
        )).thenReturn(false);

        listener.handleFinalReflectionSubmitted(
                new FinalReflectionSubmittedEvent(
                        sessionId,
                        submissionId,
                        submittedAt
                )
        );

        verifyNoInteractions(generationService);
    }

    @Test
    void frozenFinalUsesResumeInsteadOfRebuild() {
        UUID submissionId = UUID.randomUUID();

        when(dispatchGate.canDispatchFinal(
                sessionId,
                submissionId
        )).thenReturn(true);

        listener.handleFinalReflectionSubmitted(
                new FinalReflectionSubmittedEvent(
                        sessionId,
                        submissionId,
                        submittedAt
                )
        );

        verify(generationService).resumeFinal(sessionId);

        verify(generationService, never())
                .generateFinal(any(), any(), any());

        verifyNoMoreInteractions(generationService);
    }
}
