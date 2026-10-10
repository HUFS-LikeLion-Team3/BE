package com.finsight.prediction.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Integration point. Outcome and AI modules should handle this with
 * @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT).
 * No external action is performed until those handlers are implemented.
 */
public record PredictionSubmittedEvent(UUID learningSessionId, UUID userId, OffsetDateTime submittedAt) {
}
