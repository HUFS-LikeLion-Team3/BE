package com.finsight.learning;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.ReplayOutcomeRevealService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayOutcomeRevealServiceTest {
    @Mock LearningSessionRepository sessions;
    @Mock AiFeedbackRepository feedbacks;
    ReplayOutcomeRevealService service;
    UUID userId;
    UUID sessionId;
    LearningSession replay;

    @BeforeEach
    void setup() {
        service = new ReplayOutcomeRevealService(sessions, feedbacks);
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        replay = LearningSession.create(userId, UUID.randomUUID(), LearningSession.SessionType.replay);
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(replay));
    }

    private void submit() {
        var submittedAt = OffsetDateTime.parse("2026-11-17T10:00:00+09:00");
        replay.markSubmitted(submittedAt, submittedAt.minusMonths(2));
    }

    private AiFeedback feedback(AiFeedback.FeedbackStage stage, AiFeedback.FeedbackStatus status, boolean latest) {
        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getFeedbackStage()).thenReturn(stage);
        if (stage == AiFeedback.FeedbackStage.INITIAL) {
            when(feedback.getIsLatest()).thenReturn(latest);
            if (latest) when(feedback.getStatus()).thenReturn(status);
        }
        return feedback;
    }

    private void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(ApiException.class, action::run).getStatus());
    }

    @Test
    void mustSubmitBeforeClickAndMustNotRevealAutomaticallyWhenOutcomesFinish() {
        assertStatus(HttpStatus.CONFLICT, () -> service.reveal(userId, sessionId));
        submit();
        replay.advanceMarketStatus(LearningSession.LearningStatus.reflection_pending);
        assertNull(replay.getResultsRevealedAt());
        assertStatus(HttpStatus.CONFLICT, () -> service.reveal(userId, sessionId));
        assertNull(replay.getResultsRevealedAt());
    }

    @Test
    void cannotRevealUntilLatestInitialFeedbackReady() {
        submit();
        AiFeedback initialFeedback = feedback(
                AiFeedback.FeedbackStage.INITIAL, AiFeedback.FeedbackStatus.generating, true);
        when(feedbacks.findAllByLearningSessionId(sessionId)).thenReturn(List.of(initialFeedback));
        assertStatus(HttpStatus.CONFLICT, () -> service.reveal(userId, sessionId));
        assertNull(replay.getResultsRevealedAt());
    }

    @Test
    void finalFeedbackDoesNotSatisfyInitialRequirement() {
        submit();
        AiFeedback finalFeedback = feedback(
                AiFeedback.FeedbackStage.FINAL, AiFeedback.FeedbackStatus.ready, true);
        when(feedbacks.findAllByLearningSessionId(sessionId)).thenReturn(List.of(finalFeedback));
        assertStatus(HttpStatus.CONFLICT, () -> service.reveal(userId, sessionId));
    }

    @Test
    void successfulClickRevealsAndRetriesPreserveOriginalTimestamp() {
        submit();
        AiFeedback initialFeedback = feedback(
                AiFeedback.FeedbackStage.INITIAL, AiFeedback.FeedbackStatus.ready, true);
        when(feedbacks.findAllByLearningSessionId(sessionId)).thenReturn(List.of(initialFeedback));
        var first = service.reveal(userId, sessionId);
        assertNotNull(first);
        assertEquals(first, replay.getResultsRevealedAt());
        assertEquals(first, service.reveal(userId, sessionId));
        verify(feedbacks, times(1)).findAllByLearningSessionId(sessionId);
    }

    @Test
    void rejectOtherUsersWithoutLeakingFeedback() {
        submit();
        assertStatus(HttpStatus.FORBIDDEN, () -> service.reveal(UUID.randomUUID(), sessionId));
        verifyNoInteractions(feedbacks);
    }

    @Test
    void liveSessionsCannotUseReplayRevealEndpoint() {
        ReflectionTestUtils.setField(replay, "sessionType", LearningSession.SessionType.live);
        submit();
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.reveal(userId, sessionId));
        verifyNoInteractions(feedbacks);
    }
}
