package com.finsight.reflection;

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
import com.finsight.reflection.service.ReflectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReflectionServiceTest {

    @Mock LearningSessionRepository sessions;
    @Mock SessionTargetRepository targets;
    @Mock MarketObservationRepository observations;
    @Mock ReflectionNoteRepository notes;
    @Mock ReflectionSubmissionRepository submissions;

    ReflectionService service;
    final UUID owner = UUID.randomUUID();
    final UUID sessionId = UUID.randomUUID();
    final UUID targetId = UUID.randomUUID();
    LearningSession session;

    @BeforeEach
    void setup() {
        service = new ReflectionService(sessions, targets, observations, notes, submissions);
        session = LearningSession.create(owner, UUID.randomUUID());
        ReflectionTestUtils.setField(session, "id", sessionId);
    }

    @Test
    void getReturnsEmptyListWhenNoNotes() {
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(notes.findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(sessionId))
                .thenReturn(List.of());
        assertTrue(service.findAll(owner, sessionId).isEmpty());
    }

    @Test
    void getRejectsAnotherUser() {
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.findAll(UUID.randomUUID(), sessionId));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(notes);
    }

    @Test
    void intermediateRequiresTerminatedObservation() {
        prepareSession(LearningSession.LearningStatus.observing);
        stubTarget();
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(observation(1, MarketObservation.OutcomeStatus.pending)));
        ApiException ex = assertThrows(ApiException.class, () -> service.create(owner, sessionId,
                new ReflectionCreateRequest(UUID.randomUUID(), targetId, "day_1",
                        "new_insight", "중간 메모", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(submissions, never()).save(any());
    }

    @Test
    void intermediateRetryReturnsFirstResponseWithoutAnotherInsert() {
        prepareSession(LearningSession.LearningStatus.observing);
        stubTarget();
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(observation(1, MarketObservation.OutcomeStatus.unavailable)));
        UUID requestId = UUID.randomUUID();
        ReflectionCreateRequest request = new ReflectionCreateRequest(requestId, targetId,
                "day_1", "new_insight", "판단이 달라졌습니다.", null);
        AtomicReference<ReflectionSubmission> savedSubmission = new AtomicReference<>();
        AtomicReference<ReflectionNote> savedNote = new AtomicReference<>();
        when(submissions.save(any())).thenAnswer(inv -> {
            ReflectionSubmission submission = inv.getArgument(0);
            savedSubmission.set(submission);
            return submission;
        });
        when(notes.save(any())).thenAnswer(inv -> {
            ReflectionNote note = inv.getArgument(0);
            savedNote.set(note);
            return note;
        });

        ReflectionNoteResponse first = (ReflectionNoteResponse) service.create(owner, sessionId, request);
        when(submissions.findByLearningSessionIdAndClientRequestId(sessionId, requestId))
                .thenReturn(Optional.of(savedSubmission.get()));
        when(notes.findAllBySubmissionIdOrderByCreatedAtAscIdAsc(savedSubmission.get().getId()))
                .thenReturn(List.of(savedNote.get()));
        ReflectionNoteResponse repeated = (ReflectionNoteResponse) service.create(owner, sessionId, request);
        assertEquals(first, repeated);
        verify(submissions, times(1)).save(any());
        verify(notes, times(1)).save(any());
    }

    @Test
    void sameClientRequestIdWithChangedBodyIsConflict() {
        prepareSession(LearningSession.LearningStatus.observing);
        stubTarget();
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(observation(5, MarketObservation.OutcomeStatus.ready)));
        UUID requestId = UUID.randomUUID();
        when(notes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionCreateRequest original = new ReflectionCreateRequest(requestId, targetId,
                "day_5", "added_perspective", "기존 내용", null);
        ReflectionSubmission existing = captureSubmission(original);
        when(submissions.findByLearningSessionIdAndClientRequestId(sessionId, requestId))
                .thenReturn(Optional.of(existing));
        ApiException ex = assertThrows(ApiException.class, () -> service.create(owner, sessionId,
                new ReflectionCreateRequest(requestId, targetId, "day_5",
                        "added_perspective", "변경 내용", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void finalPersistsOnlyNonblankAnswersAndMarksCompleted() {
        prepareSession(LearningSession.LearningStatus.reflection_pending);
        stubTarget();
        SessionTarget selectedTarget = targets.findById(targetId).orElseThrow();
        when(targets.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selectedTarget));
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(
                        observation(1, MarketObservation.OutcomeStatus.ready),
                        observation(5, MarketObservation.OutcomeStatus.ready),
                        observation(20, MarketObservation.OutcomeStatus.unavailable)));
        when(submissions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionCreateRequest request = new ReflectionCreateRequest(UUID.randomUUID(), null,
                "final", null, null,
                List.of(new ReflectionCreateRequest.Answer("learned", "배운 점"),
                        new ReflectionCreateRequest.Answer("missed_variable", "  "),
                        new ReflectionCreateRequest.Answer("next_check", "다음 확인")));

        FinalReflectionResponse response = (FinalReflectionResponse) service.create(owner, sessionId, request);
        assertEquals("completed", response.sessionStatus());
        assertEquals(2, response.notes().size());
        assertEquals("learned", response.notes().get(0).promptKey());
        assertNotNull(response.completedAt());
        assertEquals(LearningSession.LearningStatus.completed, session.getStatus());
        verify(notes).saveAll(argThat(saved -> {
            int count = 0;
            for (Object ignored : saved) { count++; }
            return count == 2;
        }));
    }

    @Test
    void finalDoesNotCompleteWhileAnyOutcomeIsPending() {
        prepareSession(LearningSession.LearningStatus.reflection_pending);
        stubTarget();
        SessionTarget selectedTarget = targets.findById(targetId).orElseThrow();
        when(targets.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(selectedTarget));
        when(observations.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(observation(1, MarketObservation.OutcomeStatus.ready),
                        observation(5, MarketObservation.OutcomeStatus.ready),
                        observation(20, MarketObservation.OutcomeStatus.pending)));
        ReflectionCreateRequest request = new ReflectionCreateRequest(UUID.randomUUID(), null,
                "final", null, null,
                List.of(new ReflectionCreateRequest.Answer("learned", "배운 점")));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.create(owner, sessionId, request)).getStatus());
        assertEquals(LearningSession.LearningStatus.reflection_pending, session.getStatus());
        verify(submissions, never()).save(any());
    }

    private ReflectionSubmission captureSubmission(ReflectionCreateRequest original) {
        AtomicReference<ReflectionSubmission> captured = new AtomicReference<>();
        // The stubs were set by the calling test.
        doAnswer(inv -> {
            ReflectionSubmission result = inv.getArgument(0);
            captured.set(result);
            return result;
        }).when(submissions).save(any());
        service.create(owner, sessionId, original);
        return captured.get();
    }

    private void prepareSession(LearningSession.LearningStatus status) {
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "submittedAt", OffsetDateTime.now());
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
    }

    private void stubTarget() {
        SessionTarget target = SessionTarget.create(sessionId, UUID.randomUUID(), 1);
        ReflectionTestUtils.setField(target, "id", targetId);
        when(targets.findById(targetId)).thenReturn(Optional.of(target));
    }

    private MarketObservation observation(int horizon, MarketObservation.OutcomeStatus status) {
        MarketObservation observation = new MarketObservation();
        ReflectionTestUtils.setField(observation, "horizon", horizon);
        ReflectionTestUtils.setField(observation, "status", status);
        return observation;
    }
}
