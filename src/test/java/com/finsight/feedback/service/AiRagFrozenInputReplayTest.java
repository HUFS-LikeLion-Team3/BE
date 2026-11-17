package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.reflection.repository.ReflectionNoteRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiRagFrozenInputReplayTest {
    @Test
    void finalReusesReplayInitialHistoricalCutoffNotActualSubmissionTime() {
        UUID sessionId = UUID.randomUUID();
        UUID feedbackId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-11-17T10:00:00+09:00");
        OffsetDateTime referenceAt = OffsetDateTime.parse("2026-08-01T16:00:00+09:00");
        LearningSession replay = LearningSession.create(UUID.randomUUID(), UUID.randomUUID(),
                LearningSession.SessionType.replay);
        replay.markSubmitted(submittedAt, referenceAt);

        var feedbacks = mock(AiFeedbackRepository.class);
        var snapshots = mock(AiFeedbackInputSnapshotRepository.class);
        var sessions = mock(LearningSessionRepository.class);
        var baselines = mock(SessionTargetBaselineRepository.class);
        var observations = mock(MarketObservationRepository.class);
        var reflections = mock(ReflectionNoteRepository.class);
        var initialFeedback = mock(AiFeedback.class);
        var initialSnapshot = mock(AiFeedbackInputSnapshot.class);

        when(feedbacks.findByLearningSessionIdAndFeedbackStageAndVersion(
                sessionId, AiFeedback.FeedbackStage.INITIAL, 1))
                .thenReturn(Optional.of(initialFeedback));
        when(initialFeedback.getId()).thenReturn(feedbackId);
        when(snapshots.findByAiFeedbackId(feedbackId)).thenReturn(Optional.of(initialSnapshot));
        when(initialSnapshot.getRequestSnapshot()).thenReturn(Map.of(
                "inputCutoffAt", referenceAt.toString(),
                "news", Map.of("title", "Historical news"),
                "prediction", Map.of("targets", List.of(Map.of("sessionTargetId", targetId.toString()))),
                "facts", List.of(),
                "approvedSources", List.of()
        ));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(replay));

        var builder = new AiRagFrozenInputService(
                feedbacks, snapshots, sessions, baselines, observations, reflections);
        var result = builder.buildFinal(sessionId, submittedAt.plusDays(1));

        assertEquals("final", result.requestSnapshot().get("feedbackStage"));
        assertEquals("Historical news", ((Map<?, ?>) result.requestSnapshot().get("news")).get("title"));
        assertEquals(referenceAt.toString(), initialSnapshot.getRequestSnapshot().get("inputCutoffAt"));
    }
}
