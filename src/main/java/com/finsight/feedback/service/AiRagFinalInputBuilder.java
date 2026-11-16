package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.reflection.repository.ReflectionNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiRagFinalInputBuilder {

    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final LearningSessionRepository sessionRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final ReflectionNoteRepository noteRepository;

    private final JsonMapper mapper =
            JsonMapper.builder().build();

    public OffsetDateTime resolveCutoff(
            UUID sessionId,
            OffsetDateTime fallback
    ) {
        AiFeedback initial = feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.INITIAL,
                        1
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_snapshot_missing"
                        )
                );

        Map<String, Object> original = snapshotRepository
                .findByAiFeedbackId(initial.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_snapshot_missing"
                        )
                )
                .getRequestSnapshot();

        List<Map<String, Object>> targets =
                list(
                        object(original.get("prediction"))
                                .get("targets")
                );

        return targets.stream()
                .map(target ->
                        UUID.fromString(
                                String.valueOf(
                                        target.get("sessionTargetId")
                                )
                        )
                )
                .flatMap(id ->
                        observationRepository
                                .findAllBySessionTargetIdOrderByHorizonAsc(
                                        id
                                )
                                .stream()
                )
                .filter(observation ->
                        observation.getStatus()
                                != MarketObservation.OutcomeStatus.pending
                )
                .map(observation ->
                        observation.getObservedAt() != null
                                ? observation.getObservedAt()
                                : observation.getUpdatedAt()
                )
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(fallback);
    }

    public FeedbackGenerationInput build(
            UUID sessionId,
            OffsetDateTime outcomeCutoffAt
    ) {
        AiFeedback first = feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        AiFeedback.FeedbackStage.INITIAL,
                        1
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_snapshot_missing"
                        )
                );

        AiFeedbackInputSnapshot saved = snapshotRepository
                .findByAiFeedbackId(first.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_snapshot_missing"
                        )
                );

        Map<String, Object> original =
                saved.getRequestSnapshot();

        if (original == null
                || !(original.get("news") instanceof Map)
                || !(original.get("facts") instanceof List)
                || !(original.get("prediction") instanceof Map)
                || !(original.get("inputCutoffAt") instanceof String)) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        OffsetDateTime submittedAt;

        try {
            submittedAt = OffsetDateTime.parse(
                    (String) original.get("inputCutoffAt")
            );

        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "initial_snapshot_missing",
                    e
            );
        }

        var session = sessionRepository.findById(sessionId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_submission_changed"
                        )
                );

        if (session.getSubmittedAt() == null
                || !session.getSubmittedAt()
                .toInstant()
                .equals(submittedAt.toInstant())) {
            throw new IllegalStateException(
                    "initial_submission_changed"
            );
        }

        Map<String, Object> prediction =
                object(original.get("prediction"));

        List<Map<String, Object>> frozenTargets =
                list(prediction.get("targets"));

        if (frozenTargets.isEmpty()) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        Set<UUID> selected =
                new LinkedHashSet<>();

        for (Map<String, Object> target : frozenTargets) {
            Object id = target.get("sessionTargetId");
            Object marketId = target.get("marketTargetId");

            if (!(id instanceof String)
                    || !(marketId instanceof String)) {
                throw new IllegalStateException(
                        "initial_snapshot_missing"
                );
            }

            selected.add(UUID.fromString((String) id));
        }

        if (selected.size() != frozenTargets.size()) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        List<Map<String, Object>> baselineRows =
                new ArrayList<>();

        List<Map<String, Object>> resultTargets =
                new ArrayList<>();

        for (UUID targetId : selected) {
            SessionTargetBaseline baseline =
                    baselineRepository
                            .findBySessionTargetId(targetId)
                            .orElse(null);

            if (baseline == null) {
                baselineRows.add(
                        map(
                                "sessionTargetId",
                                targetId.toString(),
                                "baseline",
                                null
                        )
                );

            } else {
                baselineRows.add(
                        map(
                                "sessionTargetId",
                                targetId.toString(),
                                "status",
                                baseline.getStatus().name(),
                                "expectedBaselineDate",
                                str(baseline.getExpectedBaselineDate()),
                                "baselineDeadlineDate",
                                str(baseline.getBaselineDeadlineDate()),
                                "baselineSessionDate",
                                str(baseline.getBaselineSessionDate()),
                                "baselineAt",
                                str(baseline.getBaselineAt()),
                                "baselineValue",
                                baseline.getBaselineValue(),
                                "unit",
                                baseline.getUnit(),
                                "dataSourceName",
                                baseline.getDataSourceName(),
                                "dataSourceUrl",
                                baseline.getDataSourceUrl(),
                                "unavailableReason",
                                baseline.getUnavailableReason()
                        )
                );
            }

            List<Map<String, Object>> observationRows =
                    new ArrayList<>();

            for (MarketObservation observation :
                    observationRepository
                            .findAllBySessionTargetIdOrderByHorizonAsc(
                                    targetId
                            )) {

                observationRows.add(
                        map(
                                "horizon",
                                observation.getHorizon(),
                                "status",
                                observation.getStatus().name(),
                                "exceptionStatus",
                                observation.getExceptionStatus().name(),
                                "referenceAt",
                                str(observation.getReferenceAt()),
                                "expectedObservationDate",
                                str(observation.getExpectedObservationDate()),
                                "observationDeadlineDate",
                                str(observation.getObservationDeadlineDate()),
                                "observationSessionDate",
                                str(observation.getObservationSessionDate()),
                                "observedAt",
                                str(observation.getObservedAt()),
                                "observedValue",
                                observation.getObservedValue(),
                                "observationDelaySessions",
                                observation.getObservationDelaySessions(),
                                "changeValue",
                                observation.getChangeValue(),
                                "changePercent",
                                observation.getChangePercent(),
                                "usableForAiEvidence",
                                observation.isUsableForAiEvidence(),
                                "unit",
                                observation.getUnit(),
                                "dataSourceName",
                                observation.getDataSourceName(),
                                "dataSourceUrl",
                                observation.getDataSourceUrl(),
                                "unavailableReason",
                                observation.getUnavailableReason()
                        )
                );
            }

            resultTargets.add(
                    map(
                            "sessionTargetId",
                            targetId.toString(),
                            "observations",
                            observationRows
                    )
            );
        }

        List<Map<String, Object>> reflectionRows =
                new ArrayList<>();

        noteRepository
                .findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(
                        sessionId
                )
                .forEach(note -> {
                    if (note.getSessionTargetId() != null
                            && !selected.contains(
                            note.getSessionTargetId()
                    )) {
                        throw new IllegalStateException(
                                "Reflection target not in initial submission"
                        );
                    }

                    reflectionRows.add(
                            map(
                                    "sessionTargetId",
                                    str(note.getSessionTargetId()),
                                    "reflectionType",
                                    note.getReflectionType(),
                                    "promptKey",
                                    note.getPromptKey().name(),
                                    "body",
                                    note.getBody(),
                                    "createdAt",
                                    str(note.getCreatedAt())
                            )
                    );
                });

        Map<String, Object> baseline =
                map("targets", baselineRows);

        Map<String, Object> result =
                map("targets", resultTargets);

        Map<String, Object> reflection =
                map("notes", reflectionRows);

        Map<String, Object> frozen =
                new LinkedHashMap<>(original);

        frozen.put("feedbackStage", "final");

        // 최초 제출 시각과 결과 종료 시각은 별도 유지
        frozen.put(
                "inputCutoffAt",
                submittedAt.toString()
        );

        frozen.put(
                "outcomeCutoffAt",
                outcomeCutoffAt.toString()
        );

        frozen.put("marketBaseline", baseline);
        frozen.put("marketResult", result);
        frozen.put("reflection", reflection);

        String hash = hash(frozen);

        return new FeedbackGenerationInput(
                outcomeCutoffAt,
                frozen,
                prediction,
                baseline,
                result,
                reflection,
                hash,
                Set.copyOf(selected),
                Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
            Object value
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> list(
            Object value
    ) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        List<Map<String, Object>> result =
                new ArrayList<>();

        for (Object element : values) {
            result.add(object(element));
        }

        return result;
    }

    private static String str(Object value) {
        return value == null
                ? null
                : value.toString();
    }

    private static Map<String, Object> map(
            Object... keyValues
    ) {
        Map<String, Object> result =
                new LinkedHashMap<>();

        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(
                    (String) keyValues[i],
                    keyValues[i + 1]
            );
        }

        return result;
    }

    private String hash(Object value) {
        try {
            byte[] bytes = mapper
                    .writeValueAsString(value)
                    .getBytes(StandardCharsets.UTF_8);

            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
            );

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to hash FINAL snapshot",
                    e
            );
        }
    }
}