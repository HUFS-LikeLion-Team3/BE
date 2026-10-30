package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiRagContractMapperTest {

    private final AiRagContractMapper mapper =
            new AiRagContractMapper();

    private final JsonMapper json =
            JsonMapper.builder().build();

    private final UUID sessionId = UUID.randomUUID();
    private final UUID newsId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID marketId = UUID.randomUUID();

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse(
                    "2026-10-27T10:00:00+09:00"
            );

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

    private AiFeedback feedback(
            AiFeedback.FeedbackStage stage
    ) throws Exception {

        AiFeedback feedback = AiFeedback.create(
                sessionId,
                stage,
                1,
                "feedback:" + stage.getValue() + ":test",
                "p1",
                "feedback-policy-p0-v1",
                submittedAt
        );

        Field id = AiFeedback.class.getDeclaredField("id");

        id.setAccessible(true);
        id.set(feedback, UUID.randomUUID());

        return feedback;
    }

    private FeedbackGenerationInput frozen(boolean fin) {
        List<Map<String, Object>> predictions =
                new ArrayList<>();

        for (int horizon : List.of(1, 5, 20)) {
            predictions.add(
                    map(
                            "horizon",
                            horizon,
                            "direction",
                            "neutral",
                            "reason",
                            horizon == 1
                                    ? "Original reason"
                                    : null
                    )
            );
        }

        Map<String, Object> prediction =
                map(
                        "targets",
                        List.of(
                                map(
                                        "sessionTargetId",
                                        targetId.toString(),
                                        "marketTargetId",
                                        marketId.toString(),
                                        "predictions",
                                        predictions
                                )
                        )
                );

        Map<String, Object> initial =
                map(
                        "feedbackStage",
                        "initial",
                        "inputCutoffAt",
                        submittedAt.toString(),
                        "news",
                        map(
                                "id",
                                newsId.toString(),
                                "title",
                                "Historical title",
                                "briefing",
                                "Briefing"
                        ),
                        "facts",
                        List.of(
                                map(
                                        "label",
                                        "rate",
                                        "valueText",
                                        "1.5%"
                                )
                        ),
                        "prediction",
                        prediction
                );

        Map<String, Object> baselines =
                fin
                        ? map(
                        "targets",
                        List.of(
                                map(
                                        "sessionTargetId",
                                        targetId.toString(),
                                        "status",
                                        "ready",
                                        "baselineValue",
                                        25.0,
                                        "unit",
                                        "percent"
                                )
                        )
                )
                        : null;

        Map<String, Object> results =
                fin
                        ? map(
                        "targets",
                        List.of(
                                map(
                                        "sessionTargetId",
                                        targetId.toString(),
                                        "observations",
                                        List.of(
                                                map(
                                                        "horizon",
                                                        1,
                                                        "status",
                                                        "ready",
                                                        "changeValue",
                                                        1.0,
                                                        "observationDelaySessions",
                                                        2,
                                                        "usableForAiEvidence",
                                                        true
                                                )
                                        )
                                )
                        )
                )
                        : null;

        Map<String, Object> reflections =
                fin
                        ? map(
                        "notes",
                        List.of(
                                map(
                                        "reflectionType",
                                        "final",
                                        "body",
                                        "Reflection",
                                        "createdAt",
                                        "2026-10-30T18:00:00+09:00"
                                )
                        )
                )
                        : null;

        return new FeedbackGenerationInput(
                fin
                        ? submittedAt.plusDays(3)
                        : submittedAt,
                initial,
                prediction,
                baselines,
                results,
                reflections,
                "hash",
                Set.of(targetId),
                Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(
            String data
    ) throws Exception {
        return (Map<String, Object>) json.readValue(
                data,
                Map.class
        );
    }

    @Test
    void initialPayloadContainsOnlySubmittedDataAndNullReasons()
            throws Exception {

        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.INITIAL
        );

        Map<String, Object> envelope = read(
                mapper.envelope(
                        feedback,
                        frozen(false),
                        "sha256:" + "a".repeat(64),
                        null
                )
        );

        assertEquals(
                feedback.getId().toString(),
                envelope.get("feedbackId")
        );

        assertEquals(
                64,
                ((String) envelope.get("beInputHash")).length()
        );

        assertFalse(
                envelope.containsKey("initialJobId")
        );

        assertEquals(
                marketId.toString(),
                ((Map<?, ?>) envelope.get("bindings"))
                        .get(targetId.toString())
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> input =
                (Map<String, Object>) envelope.get("input");

        assertEquals("initial", input.get("stage"));

        assertEquals(
                submittedAt.toString(),
                input.get("submittedAt")
        );

        assertFalse(input.containsKey("results"));

        assertFalse(
                input.containsKey("reflections")
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> predictions =
                (List<Map<String, Object>>)
                        input.get("predictions");

        assertEquals(3, predictions.size());

        assertEquals(
                "Original reason",
                predictions.get(0).get("reason")
        );

        assertNull(
                predictions.get(1).get("reason")
        );
    }

    @Test
    void finalPayloadRetainsFrozenOriginalAndMapsActualObservation()
            throws Exception {

        AiFeedback feedback = feedback(
                AiFeedback.FeedbackStage.FINAL
        );

        UUID initialJobId = UUID.randomUUID();

        Map<String, Object> envelope = read(
                mapper.envelope(
                        feedback,
                        frozen(true),
                        "sha256:" + "a".repeat(64),
                        initialJobId
                )
        );

        assertEquals(
                initialJobId.toString(),
                envelope.get("initialJobId")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> input =
                (Map<String, Object>) envelope.get("input");

        assertEquals(
                submittedAt.toString(),
                input.get("submittedAt")
        );

        assertEquals(
                submittedAt.plusDays(3).toString(),
                input.get("outcomeCutoffAt")
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) input.get("results");

        assertEquals(
                targetId.toString(),
                rows.get(0).get("sessionTargetId")
        );

        assertEquals(
                2,
                rows.get(0).get("observationDelaySessions")
        );

        assertEquals(
                1.0,
                rows.get(0).get("change")
        );

        assertFalse(
                rows.get(0).containsKey("changeValue")
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reflections =
                (List<Map<String, Object>>) input.get("reflections");

        assertEquals(
                "final",
                reflections.get(0).get("type")
        );
    }
}