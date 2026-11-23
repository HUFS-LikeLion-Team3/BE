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
    /** clientRequestId is stored in reflection_submissions, not reflection_notes. */
    public static ReflectionNoteResponse from(ReflectionNote note, UUID clientRequestId) {
        return new ReflectionNoteResponse(
                note.getId(), note.getLearningSessionId(), note.getSessionTargetId(),
                note.getReflectionType(), note.getPromptKey().name(), note.getBody(),
                note.getCreatedAt(), note.getSubmissionId(), clientRequestId
        );
    }
}
