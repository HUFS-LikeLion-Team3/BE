package com.finsight.reflection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reflection_notes")
@Getter
@NoArgsConstructor
public class ReflectionNote {

    @Id
    private UUID id;

    @Column(name = "learning_session_id", nullable = false)
    private UUID learningSessionId;

    @Column(name = "session_target_id")
    private UUID sessionTargetId;

    @Column(name = "reflection_type", nullable = false)
    private String reflectionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "prompt_key", nullable = false)
    private PromptKey promptKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    public static ReflectionNote create(UUID sessionId, UUID targetId, String type,
                                        PromptKey key, String body, ReflectionSubmission submission,
                                        OffsetDateTime now) {
        ReflectionNote note = new ReflectionNote();
        note.id = UUID.randomUUID();
        note.learningSessionId = sessionId;
        note.sessionTargetId = targetId;
        note.reflectionType = type;
        note.promptKey = key;
        note.body = body;
        note.createdAt = now;
        note.submissionId = submission.getId();
        note.clientRequestId = submission.getClientRequestId();
        return note;
    }

    public enum PromptKey { new_insight, added_perspective, learned, missed_variable, next_check }
}
