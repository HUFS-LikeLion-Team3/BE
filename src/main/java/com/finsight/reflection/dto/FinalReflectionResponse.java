package com.finsight.reflection.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FinalReflectionResponse(
        UUID submissionId,
        UUID clientRequestId,
        List<ReflectionNoteResponse> notes,
        String sessionStatus,
        OffsetDateTime completedAt
) { }
