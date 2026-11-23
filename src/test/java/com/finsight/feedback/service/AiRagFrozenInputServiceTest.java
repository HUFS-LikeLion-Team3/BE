
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiRagFrozenInputServiceTest {

    private final AiFeedbackRepository feedbacks =
            mock(AiFeedbackRepository.class);

    private final AiFeedbackInputSnapshotRepository snapshots =
            mock(AiFeedbackInputSnapshotRepository.class);

    private final LearningSessionRepository sessions =
            mock(LearningSessionRepository.class);

    private final MarketObservationRepository observations =
            mock(MarketObservationRepository.class);

    private final SessionTargetBaselineRepository baselines =
            mock(SessionTargetBaselineRepository.class);

    private final ReflectionNoteRepository reflections =
            mock(ReflectionNoteRepository.class);

    private final AiRagFrozenInputService service =
            new AiRagFrozenInputService(
                    feedbacks,
                    snapshots,
                    sessions,
                    baselines,
                    observations,
                    reflections
            );

    private final UUID sessionId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    private final OffsetDateTime submitted =
            OffsetDateTime.parse(
                    "2026-10-27T10:00:00+09:00"
            );

    private final OffsetDateTime outcomeCutoff =
            OffsetDateTime.parse(
                    "2026-10-30T18:00:00+09:00"
            );

    private void originals() {
        AiFeedback initial = mock(AiFeedback.class);

        when(initial.getId()).thenReturn(feedbackId);

        when(
                feedbacks
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                AiFeedback.FeedbackStage.INITIAL,
                                1
                        )
        ).thenReturn(Optional.of(initial));

        AiFeedbackInputSnapshot snapshot =
                mock(AiFeedbackInputSnapshot.class);

        when(snapshot.getRequestSnapshot()).thenReturn(
                Map.of(
                        "feedbackStage", "initial",
                        "inputCutoffAt", submitted.toString(),
                        "news", Map.of(
                                "id", UUID.randomUUID().toString(),
                                "title", "ORIGINAL NEWS"
                        ),
                        "facts", List.of(
                                Map.of(
                                        "label",
                                        "ORIGINAL FACT"
                                )
                        ),
                        "prediction", Map.of(
                                "targets", List.of(
                                        Map.of(
                                                "sessionTargetId",
                                                targetId.toString(),
                                                "predictions",
                                                List.of(
                                                        Map.of(
                                                                "horizon", 1,
                                                                "reason",
                                                                "ORIGINAL REASON"
                                                        )
                                                )
                                        )
                                )
                        ),
                        "approvedSources", List.of()
                )
        );

        when(
                snapshots.findByAiFeedbackId(feedbackId)
        ).thenReturn(Optional.of(snapshot));

        LearningSession learning =
                mock(LearningSession.class);

        when(learning.getSubmittedAt())
                .thenReturn(submitted);

        when(sessions.findById(sessionId))
                .thenReturn(Optional.of(learning));
    }

    @Test
    void finalPreservesOriginalAndActualOutcomes() {
        originals();

        when(
                baselines.findBySessionTargetId(targetId)
        ).thenReturn(Optional.empty());

        MarketObservation observation =
                mock(MarketObservation.class);

        when(observation.getHorizon()).thenReturn(1);

        when(observation.getStatus()).thenReturn(
                MarketObservation.OutcomeStatus.unavailable
        );

        when(observation.getExceptionStatus()).thenReturn(
                MarketObservation.OutcomeExceptionStatus.data_missing
        );

        when(observation.getObservationDelaySessions())
                .thenReturn(3);

        when(observation.getReferenceAt())
                .thenReturn(submitted);

        when(observation.getObservationDeadlineDate())
                .thenReturn(LocalDate.of(2026, 11, 10));

        when(observation.getChangeValue())
                .thenReturn(new BigDecimal("1.25"));

        when(
                observations.findAllBySessionTargetIdOrderByHorizonAsc(
                        targetId
                )
        ).thenReturn(List.of(observation));

        when(
                reflections
                        .findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(
                                sessionId
                        )
        ).thenReturn(List.of());

        FeedbackGenerationInput frozen =
                service.buildFinal(
                        sessionId,
                        outcomeCutoff
                );

        Map<String, Object> request =
                frozen.requestSnapshot();

        @SuppressWarnings("unchecked")
        Map<String, Object> news =
                (Map<String, Object>) request.get("news");

        assertEquals(
                "ORIGINAL NEWS",
                news.get("title")
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets =
                (List<Map<String, Object>>)
                        frozen.predictionSnapshot().get("targets");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> predictions =
                (List<Map<String, Object>>)
                        targets.get(0).get("predictions");

        assertEquals(
                "ORIGINAL REASON",
                predictions.get(0).get("reason")
        );

        assertEquals("final", request.get("feedbackStage"));

        assertEquals(
                outcomeCutoff.toString(),
                request.get("inputCutoffAt")
        );

        assertEquals(
                Set.of(targetId),
                frozen.allowedTargetIds()
        );

        assertEquals(64, frozen.inputHash().length());

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) request.get("marketResult");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultTargets =
                (List<Map<String, Object>>) result.get("targets");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultRows =
                (List<Map<String, Object>>)
                        resultTargets.get(0).get("observations");

        Map<String, Object> row = resultRows.get(0);

        assertEquals(
                3,
                row.get("observationDelaySessions")
        );

        assertEquals(
                submitted.toString(),
                row.get("referenceAt")
        );

        assertEquals(
                "2026-11-10",
                row.get("observationDeadlineDate")
        );

        assertEquals(
                false,
                row.get("usableForAiEvidence")
        );

        assertEquals(
                new BigDecimal("1.25"),
                row.get("changeValue")
        );
    }

    @Test
    void missingInitialSnapshotDoesNotRebuild() {
        when(
                feedbacks
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                AiFeedback.FeedbackStage.INITIAL,
                                1
                        )
        ).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.buildFinal(
                        sessionId,
                        outcomeCutoff
                )
        );

        assertEquals(
                "initial_snapshot_missing",
                error.getMessage()
        );

        verifyNoInteractions(
                observations,
                baselines,
                reflections
        );
    }

    @Test
    void modifiedSubmissionTimestampFails() {
        originals();

        LearningSession changedSession =
                mock(LearningSession.class);

        when(changedSession.getSubmittedAt())
                .thenReturn(submitted.plusMinutes(1));

        when(sessions.findById(sessionId))
                .thenReturn(Optional.of(changedSession));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.buildFinal(
                        sessionId,
                        outcomeCutoff
                )
        );

        assertEquals(
                "initial_submission_changed",
                error.getMessage()
        );

        verifyNoInteractions(
                observations,
                baselines,
                reflections
        );
    }
}
