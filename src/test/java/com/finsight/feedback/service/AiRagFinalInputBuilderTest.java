package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.reflection.repository.ReflectionNoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * #25: FINAL은 INITIAL 당시 스냅샷을 유지하고 관측·회고만 추가한다.
 * 외부 AI 서버 및 DB 연결 없이 서비스 입력 생성 규칙만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AiRagFinalInputBuilderTest {

    @Mock AiFeedbackRepository feedbackRepository;
    @Mock AiFeedbackInputSnapshotRepository snapshotRepository;
    @Mock LearningSessionRepository sessionRepository;
    @Mock SessionTargetBaselineRepository baselineRepository;
    @Mock MarketObservationRepository observationRepository;
    @Mock ReflectionNoteRepository noteRepository;

    @InjectMocks AiRagFinalInputBuilder builder;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID initialFeedbackId = UUID.randomUUID();
    private final UUID sessionTargetId = UUID.randomUUID();
    private final UUID marketTargetId = UUID.randomUUID();
    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse("2026-10-27T10:00:00+09:00");
    private final OffsetDateTime outcomeCutoffAt =
            OffsetDateTime.parse("2026-10-30T18:00:00+09:00");

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private Map<String, Object> initialSnapshot() {
        return map(
                "feedbackStage", "initial",
                "inputCutoffAt", submittedAt.toString(),
                "news", map("id", UUID.randomUUID().toString(),
                        "title", "Original news title",
                        "briefing", "Original briefing"),
                "facts", List.of(map("label", "rate", "valueText", "1.5%")),
                "prediction", map("targets", List.of(map(
                        "sessionTargetId", sessionTargetId.toString(),
                        "marketTargetId", marketTargetId.toString(),
                        "predictions", List.of(
                                map("horizon", 1, "direction", "up", "reason", "Original reason"),
                                map("horizon", 5, "direction", "down", "reason", null),
                                map("horizon", 20, "direction", "neutral", "reason", null)
                        )
                )))
        );
    }

    private void savedInitialExists(Map<String, Object> original) {
        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getId()).thenReturn(initialFeedbackId);
        when(feedbackRepository.findByLearningSessionIdAndFeedbackStageAndVersion(
                sessionId, AiFeedback.FeedbackStage.INITIAL, 1))
                .thenReturn(Optional.of(feedback));

        @SuppressWarnings("unchecked")
        Map<String, Object> predictions =
                (Map<String, Object>) original.get("prediction");
        AiFeedbackInputSnapshot snapshot = AiFeedbackInputSnapshot.create(
                initialFeedbackId, original, predictions,
                null, null, null, "a".repeat(64));
        when(snapshotRepository.findByAiFeedbackId(initialFeedbackId))
                .thenReturn(Optional.of(snapshot));
    }

    private void validSubmittedSession() {
        LearningSession session = mock(LearningSession.class);
        when(session.getSubmittedAt()).thenReturn(submittedAt);
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));
    }

    private void noResultsYet() {
        when(baselineRepository.findBySessionTargetId(sessionTargetId))
                .thenReturn(Optional.empty());
        when(observationRepository.findAllBySessionTargetIdOrderByHorizonAsc(sessionTargetId))
                .thenReturn(List.of());
        when(noteRepository.findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(sessionId))
                .thenReturn(List.of());
    }

    @Test
    void finalReusesFrozenNewsPredictionsAndOriginalSubmissionTime() {
        Map<String, Object> original = initialSnapshot();
        savedInitialExists(original);
        validSubmittedSession();
        noResultsYet();

        FeedbackGenerationInput result = builder.build(sessionId, outcomeCutoffAt);

        assertEquals(original.get("news"), result.requestSnapshot().get("news"));
        assertEquals(original.get("facts"), result.requestSnapshot().get("facts"));
        assertEquals(original.get("prediction"), result.requestSnapshot().get("prediction"));
        assertEquals(submittedAt.toString(), result.requestSnapshot().get("inputCutoffAt"));
        assertEquals(outcomeCutoffAt.toString(), result.requestSnapshot().get("outcomeCutoffAt"));
        assertEquals("final", result.requestSnapshot().get("feedbackStage"));
        assertEquals("initial", original.get("feedbackStage"),
                "FINAL 생성 중 원본 INITIAL 스냅샷을 수정하면 안 된다");
        assertFalse(original.containsKey("marketResult"));
    }

    @Test
    void missingInitialSnapshotIsNotRebuiltFromCurrentDatabase() {
        when(feedbackRepository.findByLearningSessionIdAndFeedbackStageAndVersion(
                sessionId, AiFeedback.FeedbackStage.INITIAL, 1))
                .thenReturn(Optional.empty());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> builder.build(sessionId, outcomeCutoffAt));

        assertEquals("initial_snapshot_missing", failure.getMessage());
        verifyNoInteractions(sessionRepository, baselineRepository,
                observationRepository, noteRepository);
    }

    @Test
    void changedSubmissionTimeIsRejectedInsteadOfRebuildingInitial() {
        savedInitialExists(initialSnapshot());
        LearningSession changedSession = mock(LearningSession.class);
        when(changedSession.getSubmittedAt()).thenReturn(submittedAt.plusMinutes(1));
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(changedSession));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> builder.build(sessionId, outcomeCutoffAt));

        assertEquals("initial_submission_changed", failure.getMessage());
        verifyNoInteractions(baselineRepository, observationRepository, noteRepository);
    }

    @Test
    void finalPreservesObservedDelayAndUnavailableEvidenceFlags() {
        savedInitialExists(initialSnapshot());
        validSubmittedSession();
        when(baselineRepository.findBySessionTargetId(sessionTargetId))
                .thenReturn(Optional.empty());
        when(noteRepository.findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(sessionId))
                .thenReturn(List.of());

        MarketObservation delayed = mock(MarketObservation.class);
        when(delayed.getHorizon()).thenReturn(1);
        when(delayed.getStatus()).thenReturn(MarketObservation.OutcomeStatus.ready);
        when(delayed.getExceptionStatus()).thenReturn(MarketObservation.OutcomeExceptionStatus.none);
        when(delayed.getObservationDelaySessions()).thenReturn(2);
        when(delayed.isUsableForAiEvidence()).thenReturn(true);
        when(delayed.getObservedAt()).thenReturn(outcomeCutoffAt.minusHours(1));
        when(delayed.getUnit()).thenReturn("KRW");

        MarketObservation unavailable = mock(MarketObservation.class);
        when(unavailable.getHorizon()).thenReturn(5);
        when(unavailable.getStatus()).thenReturn(MarketObservation.OutcomeStatus.unavailable);
        when(unavailable.getExceptionStatus()).thenReturn(MarketObservation.OutcomeExceptionStatus.data_missing);
        when(unavailable.getUnavailableReason()).thenReturn("No official close");
        when(unavailable.getUnit()).thenReturn("KRW");

        when(observationRepository.findAllBySessionTargetIdOrderByHorizonAsc(sessionTargetId))
                .thenReturn(List.of(delayed, unavailable));

        FeedbackGenerationInput result = builder.build(sessionId, outcomeCutoffAt);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>)
                result.marketResultSnapshot().get("targets");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) targets.getFirst().get("observations");

        assertEquals("ready", rows.get(0).get("status"));
        assertEquals(2, rows.get(0).get("observationDelaySessions"));
        assertEquals(true, rows.get(0).get("usableForAiEvidence"));
        assertEquals(outcomeCutoffAt.minusHours(1).toString(), rows.get(0).get("observedAt"));

        assertEquals("unavailable", rows.get(1).get("status"));
        assertEquals("data_missing", rows.get(1).get("exceptionStatus"));
        assertEquals(false, rows.get(1).get("usableForAiEvidence"));
        assertNull(rows.get(1).get("observedAt"));
        assertEquals("No official close", rows.get(1).get("unavailableReason"));
    }
}
