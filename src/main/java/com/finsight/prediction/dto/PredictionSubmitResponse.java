package com.finsight.prediction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PredictionSubmitResponse(UUID sessionId, String status, OffsetDateTime submittedAt) {
}
