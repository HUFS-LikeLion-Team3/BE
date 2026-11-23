package com.finsight.reflection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Groups the notes saved by one client request and makes retries idempotent. */
@Entity
@Table(name = "reflection_submissions", uniqueConstraints =
@UniqueConstraint(name = "uk_reflection_submission_session_request",
        columnNames = {"learning_session_id", "client_request_id"}))
@Getter
@NoArgsConstructor
public class ReflectionSubmission {

    @Id
    private UUID id;

    @Column(name = "learning_session_id", nullable = false)
    private UUID learningSessionId;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "reflection_type", nullable = false)
    private String reflectionType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static ReflectionSubmission create(
            UUID sessionId,
            UUID clientRequestId,
            String requestHash,
            String reflectionType
    ) {
        ReflectionSubmission submission = new ReflectionSubmission();
        submission.id = UUID.randomUUID();
        submission.learningSessionId = sessionId;
        submission.clientRequestId = clientRequestId;
        submission.requestHash = requestHash;
        submission.reflectionType = reflectionType;
        submission.createdAt = OffsetDateTime.now();
        return submission;
    }
}
