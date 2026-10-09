package com.finsight.reflection.dto;

import com.finsight.reflection.entity.ReflectionNote;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReflectionNoteResponse(
        UUID id,
        UUID learningSessionId,
        UUID sessionTargetId,
        String reflectionType,
        String promptKey,
        String body,
        OffsetDateTime createdAt,
        UUID submissionId,
        UUID clientRequestId
) {
    public static ReflectionNoteResponse from(ReflectionNote note) {
        return new ReflectionNoteResponse(
                note.getId(), note.getLearningSessionId(), note.getSessionTargetId(),
                note.getReflectionType(), note.getPromptKey().name(), note.getBody(),
                note.getCreatedAt(), note.getSubmissionId(), note.getClientRequestId()
        );
    }
}
