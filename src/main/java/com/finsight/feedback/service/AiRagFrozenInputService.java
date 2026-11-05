
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.entity.LearningSession;
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

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiRagFrozenInputService {

    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final LearningSessionRepository sessionRepository;
    private final SessionTargetBaselineRepository baselineRepository;
    private final MarketObservationRepository observationRepository;
    private final ReflectionNoteRepository reflectionRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public FeedbackGenerationInput buildFinal(
            UUID sessionId,
            OffsetDateTime outcomeCutoffAt
    ) {
        if (outcomeCutoffAt == null) {
            throw new IllegalArgumentException(
                    "outcomeCutoffAt is required"
            );
        }

        AiFeedback initialFeedback = feedbackRepository
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

        AiFeedbackInputSnapshot initial = snapshotRepository
                .findByAiFeedbackId(initialFeedback.getId())
                .orElseThrow(() ->
                        new IllegalStateException(
                                "initial_snapshot_missing"
                        )
                );

        Map<String, Object> original =
                initial.getRequestSnapshot();

        if (original == null
                || original.get("news") == null
                || original.get("prediction") == null
                || original.get("facts") == null
                || original.get("inputCutoffAt") == null) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        OffsetDateTime submittedAt;

        try {
            submittedAt = OffsetDateTime.parse(
                    String.valueOf(
                            original.get("inputCutoffAt")
                    )
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "initial_snapshot_missing",
                    e
            );
        }

        LearningSession session = sessionRepository
                .findById(sessionId)
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

        Map<String, Object> originalPredictions =
                asMap(original.get("prediction"));

        Set<UUID> frozenTargetIds =
                new LinkedHashSet<>();

        for (Map<String, Object> target :
                mapList(originalPredictions.get("targets"))) {

            if (target.get("sessionTargetId") == null) {
                throw new IllegalStateException(
                        "initial_snapshot_missing"
                );
            }

            frozenTargetIds.add(
                    UUID.fromString(
                            String.valueOf(
                                    target.get("sessionTargetId")
                            )
                    )
            );
        }

        if (frozenTargetIds.isEmpty()) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        Map<String, Object> baselines =
                buildBaselines(frozenTargetIds);

        Map<String, Object> results =
                buildResults(frozenTargetIds);

        Map<String, Object> reflections =
                buildReflections(sessionId);

        Map<String, Object> fixed =
                new LinkedHashMap<>();

        fixed.put("feedbackStage", "final");
        fixed.put(
                "inputCutoffAt",
                outcomeCutoffAt.toString()
        );

        // INITIAL 당시의 원본 정보를 그대로 사용
        fixed.put("news", original.get("news"));
        fixed.put("facts", original.get("facts"));
        fixed.put("prediction", originalPredictions);

        // FINAL 시점에 확정된 데이터만 추가
        fixed.put("marketBaseline", baselines);
        fixed.put("marketResult", results);
        fixed.put("reflection", reflections);

        // 기존 스냅샷 호환용.
        // 실제 RAG 자료 검색은 AI 서버에서 처리.
        fixed.put(
                "approvedSources",
                original.get("approvedSources")
        );

        return new FeedbackGenerationInput(
                outcomeCutoffAt,
                fixed,
                originalPredictions,
                baselines,
                results,
                reflections,
                sha256(fixed),
                Set.copyOf(frozenTargetIds),
                Map.of()
        );
    }

    private Map<String, Object> buildBaselines(
            Set<UUID> frozenTargetIds
    ) {
        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (UUID id : frozenTargetIds) {
            SessionTargetBaseline b = baselineRepository
                    .findBySessionTargetId(id)
                    .orElse(null);

            if (b == null) {
                rows.add(
                        map(
                                "sessionTargetId", id.toString(),
                                "baseline", null
                        )
                );
                continue;
            }

            rows.add(map(
                    "sessionTargetId", id.toString(),
                    "status", b.getStatus().name(),
                    "baselineSessionDate",
                    str(b.getBaselineSessionDate()),
                    "baselineAt", str(b.getBaselineAt()),
                    "baselineValue", b.getBaselineValue(),
                    "unit", b.getUnit(),
                    "dataSourceName", b.getDataSourceName(),
                    "dataSourceUrl", b.getDataSourceUrl(),
                    "unavailableReason",
                    b.getUnavailableReason(),
                    "expectedBaselineDate",
                    str(b.getExpectedBaselineDate()),
                    "baselineDeadlineDate",
                    str(b.getBaselineDeadlineDate())
            ));
        }

        return map("targets", rows);
    }

    private Map<String, Object> buildResults(
            Set<UUID> frozenTargetIds
    ) {
        List<Map<String, Object>> targets =
                new ArrayList<>();

        for (UUID id : frozenTargetIds) {

            List<Map<String, Object>> rows =
                    observationRepository
                            .findAllBySessionTargetIdOrderByHorizonAsc(id)
                            .stream()
                            .sorted(
                                    Comparator.comparing(
                                            MarketObservation::getHorizon
                                    )
                            )
                            .map(o -> map(
                                    "horizon", o.getHorizon(),
                                    "status", o.getStatus().name(),
                                    "exceptionStatus",
                                    o.getExceptionStatus().name(),
                                    "referenceAt",
                                    str(o.getReferenceAt()),
                                    "expectedObservationDate",
                                    str(o.getExpectedObservationDate()),
                                    "observationDeadlineDate",
                                    str(o.getObservationDeadlineDate()),
                                    "observationSessionDate",
                                    str(o.getObservationSessionDate()),
                                    "observedAt",
                                    str(o.getObservedAt()),
                                    "observedValue",
                                    o.getObservedValue(),
                                    "observationDelaySessions",
                                    o.getObservationDelaySessions(),
                                    "changeValue",
                                    o.getChangeValue(),
                                    "changePercent",
                                    o.getChangePercent(),
                                    "usableForAiEvidence",
                                    o.isUsableForAiEvidence(),
                                    "unit", o.getUnit(),
                                    "dataSourceName",
                                    o.getDataSourceName(),
                                    "dataSourceUrl",
                                    o.getDataSourceUrl(),
                                    "unavailableReason",
                                    o.getUnavailableReason()
                            ))
                            .toList();

            targets.add(
                    map(
                            "sessionTargetId",
                            id.toString(),
                            "observations",
                            rows
                    )
            );
        }

        return map("targets", targets);
    }

    private Map<String, Object> buildReflections(
            UUID sessionId
    ) {
        List<Map<String, Object>> notes =
                reflectionRepository
                        .findAllByLearningSessionIdOrderByCreatedAtAscIdAsc(
                                sessionId
                        )
                        .stream()
                        .map(note -> map(
                                "id", note.getId().toString(),
                                "sessionTargetId",
                                str(note.getSessionTargetId()),
                                "reflectionType",
                                note.getReflectionType(),
                                "promptKey",
                                note.getPromptKey().name(),
                                "body", note.getBody(),
                                "createdAt",
                                str(note.getCreatedAt())
                        ))
                        .toList();

        return map("notes", notes);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(
            Object value
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "initial_snapshot_missing"
            );
        }

        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> mapList(
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
            result.add(asMap(element));
        }

        return result;
    }

    private static String str(Object value) {
        return value == null
                ? null
                : value.toString();
    }

    private String sha256(
            Map<String, Object> value
    ) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);

            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(json)
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to hash frozen FINAL input",
                    e
            );
        }
    }
}
