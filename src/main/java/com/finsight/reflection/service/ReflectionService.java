package com.finsight.reflection.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import com.finsight.reflection.dto.FinalReflectionResponse;
import com.finsight.reflection.dto.ReflectionCreateRequest;
import com.finsight.reflection.dto.ReflectionNoteResponse;
import com.finsight.reflection.entity.ReflectionNote;
import com.finsight.reflection.entity.ReflectionSubmission;
import com.finsight.reflection.repository.ReflectionNoteRepository;
import com.finsight.reflection.repository.ReflectionSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReflectionService {

    private final LearningSessionRepository sessionRepository;
    private final SessionTargetRepository targetRepository;
    private final MarketObservationRepository observationRepository;
    private final ReflectionNoteRepository noteRepository;
    private final ReflectionSubmissionRepository submissionRepository;

    public List<ReflectionNoteResponse> findAll(UUID userId, UUID sessionId) {
        requireOwnedSession(userId, sessionId, false);
        return noteRepository.findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(sessionId)
                .stream().map(ReflectionNoteResponse::from).toList();
    }

    @Transactional
    public Object create(UUID userId, UUID sessionId, ReflectionCreateRequest request) {
        if (request == null || request.clientRequestId() == null) {
            throw badRequest();
        }

        // Lock the session: simultaneous retries must not create two submissions.
        LearningSession session = requireOwnedSession(userId, sessionId, true);
        String hash = hashRequest(request);

        // A previously accepted request wins over the current session state (even completed).
        var previous = submissionRepository.findByLearningSessionIdAndClientRequestId(
                sessionId, request.clientRequestId());
        if (previous.isPresent()) {
            ReflectionSubmission submission = previous.get();
            if (!submission.getRequestHash().equals(hash)) {
                throw conflict();
            }
            return replaySavedResponse(session, submission);
        }

        if ("day_1".equals(request.reflectionType()) || "day_5".equals(request.reflectionType())) {
            return saveIntermediate(session, request, hash);
        }
        if ("final".equals(request.reflectionType())) {
            return saveFinal(session, request, hash);
        }
        // replay_final is a P1-only value, not accepted in P0.
        throw badRequest();
    }

    private ReflectionNoteResponse saveIntermediate(LearningSession session,
                                                     ReflectionCreateRequest request, String hash) {
        if (request.sessionTargetId() == null || request.answers() != null
                || request.body() == null || request.body().isBlank()) {
            throw badRequest();
        }
        ReflectionNote.PromptKey key;
        int horizon;
        if ("day_1".equals(request.reflectionType())
                && "new_insight".equals(request.promptKey())) {
            key = ReflectionNote.PromptKey.new_insight;
            horizon = 1;
        } else if ("day_5".equals(request.reflectionType())
                && "added_perspective".equals(request.promptKey())) {
            key = ReflectionNote.PromptKey.added_perspective;
            horizon = 5;
        } else {
            throw badRequest();
        }

        SessionTarget target = targetRepository.findById(request.sessionTargetId())
                .orElseThrow(ReflectionService::notFound);
        if (!session.getId().equals(target.getLearningSessionId()) || !target.isSelected()) {
            throw badRequest();
        }
        if (session.getSubmittedAt() == null
                || session.getStatus() == LearningSession.LearningStatus.drafting
                || session.getStatus() == LearningSession.LearningStatus.completed) {
            throw conflict();
        }
        if (!hasTerminatedOutcome(target.getId(), horizon)) {
            throw conflict();
        }

        ReflectionSubmission submission = submissionRepository.save(
                ReflectionSubmission.create(session.getId(), request.clientRequestId(), hash));
        ReflectionNote note = noteRepository.save(ReflectionNote.create(
                session.getId(), target.getId(),
                request.reflectionType(),
                key, request.body(), submission, OffsetDateTime.now()));
        return ReflectionNoteResponse.from(note);
    }

    private FinalReflectionResponse saveFinal(LearningSession session,
                                              ReflectionCreateRequest request, String hash) {
        if (request.sessionTargetId() != null || request.promptKey() != null
                || request.body() != null || request.answers() == null) {
            throw badRequest();
        }
        List<ReflectionCreateRequest.Answer> validAnswers = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ReflectionCreateRequest.Answer answer : request.answers()) {
            if (answer == null || !isFinalKey(answer.promptKey()) || !keys.add(answer.promptKey())
                    || answer.body() == null) {
                throw badRequest();
            }
            if (!answer.body().isBlank()) {
                validAnswers.add(answer);
            }
        }
        if (validAnswers.isEmpty()) {
            throw badRequest();
        }
        if (session.getStatus() != LearningSession.LearningStatus.reflection_pending) {
            throw conflict();
        }
        List<SessionTarget> selected = targetRepository.findAllByLearningSessionId(session.getId())
                .stream().filter(SessionTarget::isSelected).toList();
        if (selected.isEmpty()) {
            throw conflict();
        }
        for (SessionTarget target : selected) {
            for (int horizon : List.of(1, 5, 20)) {
                if (!hasTerminatedOutcome(target.getId(), horizon)) {
                    throw conflict();
                }
            }
        }

        OffsetDateTime completedAt = OffsetDateTime.now();
        ReflectionSubmission submission = submissionRepository.save(
                ReflectionSubmission.create(session.getId(), request.clientRequestId(), hash));
        List<ReflectionNote> notes = new ArrayList<>();
        for (ReflectionCreateRequest.Answer answer : validAnswers) {
            notes.add(ReflectionNote.create(session.getId(), null,
                    "final",
                    ReflectionNote.PromptKey.valueOf(answer.promptKey()),
                    answer.body(), submission, completedAt));
        }
        noteRepository.saveAll(notes);
        session.markCompleted(completedAt);

        // TODO: After the AI generation service has been merged, request final feedback
        // AFTER the transaction commits. AI failures must not revert completed sessions.
        return new FinalReflectionResponse(submission.getId(), request.clientRequestId(),
                finalNotes(notes), session.getStatus().name(), session.getCompletedAt());
    }

    private Object replaySavedResponse(LearningSession session, ReflectionSubmission submission) {
        List<ReflectionNote> saved = noteRepository.findAllBySubmissionIdOrderByCreatedAtAscIdAsc(
                submission.getId());
        if (saved.isEmpty()) {
            throw new IllegalStateException("No reflection notes for a committed submission");
        }
        if ("final".equals(saved.getFirst().getReflectionType())) {
            return new FinalReflectionResponse(submission.getId(), submission.getClientRequestId(),
                    finalNotes(saved), LearningSession.LearningStatus.completed.name(),
                    session.getCompletedAt());
        }
        return ReflectionNoteResponse.from(saved.getFirst());
    }

    private static List<ReflectionNoteResponse> finalNotes(List<ReflectionNote> notes) {
        return notes.stream()
                .sorted(Comparator.comparingInt(note -> switch (note.getPromptKey()) {
                    case learned -> 0;
                    case missed_variable -> 1;
                    case next_check -> 2;
                    default -> 3;
                }))
                .map(ReflectionNoteResponse::from).toList();
    }

    private boolean hasTerminatedOutcome(UUID targetId, int horizon) {
        return observationRepository.findAllBySessionTargetIdOrderByHorizonAsc(targetId)
                .stream()
                .anyMatch(o -> o.getHorizon() == horizon
                        && (o.getStatus() == MarketObservation.OutcomeStatus.ready
                        || o.getStatus() == MarketObservation.OutcomeStatus.unavailable));
    }

    private LearningSession requireOwnedSession(UUID userId, UUID sessionId, boolean lock) {
        LearningSession session = (lock ? sessionRepository.findByIdForUpdate(sessionId)
                        : sessionRepository.findById(sessionId))
                .orElseThrow(ReflectionService::notFound);
        if (!session.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "해당 기록에 접근할 권한이 없습니다.");
        }
        return session;
    }

    // Canonical representation of the API-defined fields, including absent fields and
    // answer ordering. No dependency on a request body's JSON key ordering.
    private String hashRequest(ReflectionCreateRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeNullable(out, request.clientRequestId().toString());
            writeNullable(out, request.sessionTargetId() == null
                    ? null : request.sessionTargetId().toString());
            writeNullable(out, request.reflectionType());
            writeNullable(out, request.promptKey());
            writeNullable(out, request.body());
            if (request.answers() == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(request.answers().size());
                for (ReflectionCreateRequest.Answer answer : request.answers()) {
                    out.writeBoolean(answer != null);
                    if (answer != null) {
                        writeNullable(out, answer.promptKey());
                        writeNullable(out, answer.body());
                    }
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return java.util.HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash the reflection request", e);
        }
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(utf8.length);
            out.write(utf8);
        }
    }

    private static boolean isFinalKey(String key) {
        return "learned".equals(key) || "missed_variable".equals(key)
                || "next_check".equals(key);
    }

    private static ApiException badRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT,
                "현재 상태에서는 회고를 저장할 수 없거나 이미 처리된 요청과 충돌합니다.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "요청한 세션 또는 기록을 찾을 수 없습니다.");
    }
}
