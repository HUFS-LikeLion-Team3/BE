package com.finsight.reflection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FinalReflectionSubmittedEvent(
        UUID learningSessionId,
        UUID reflectionSubmissionId,
        OffsetDateTime completedAt
) {
}