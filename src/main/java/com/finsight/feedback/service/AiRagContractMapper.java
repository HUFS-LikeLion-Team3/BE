package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class AiRagContractMapper {

    private final JsonMapper mapper =
            JsonMapper.builder().build();

    public String envelope(
            AiFeedback feedback,
            FeedbackGenerationInput snapshot,
            String packageId,
            UUID initialJobId
    ) {
        if (packageId == null
                || !packageId.startsWith("sha256:")) {
            throw new IllegalStateException(
                    "AI_RAG_PACKAGE_ID must be a registered sha256 package ID"
            );
        }

        Map<String, Object> original =
                snapshot.requestSnapshot();

        Map<String, Object> news =
                object(original.get("news"));

        Map<String, Object> predictionsSnapshot =
                object(original.get("prediction"));

        List<Map<String, Object>> targets =
                objects(predictionsSnapshot.get("targets"));

        if (targets.isEmpty() || targets.size() > 3) {
            throw new IllegalStateException(
                    "AI expects 1 to 3 selected session targets"
            );
        }

        Map<String, Object> bindings =
                new LinkedHashMap<>();

        Set<String> marketIds = new HashSet<>();

        List<Map<String, Object>> predictions =
                new ArrayList<>();

        for (Map<String, Object> target : targets) {
            String sessionTargetId =
                    required(target, "sessionTargetId");

            String marketTargetId =
                    required(target, "marketTargetId");

            if (bindings.putIfAbsent(
                    sessionTargetId,
                    marketTargetId
            ) != null || !marketIds.add(marketTargetId)) {
                throw new IllegalStateException(
                        "Duplicated AI target binding"
                );
            }

            List<Map<String, Object>> submitted =
                    objects(target.get("predictions"));

            Set<Integer> horizons = new HashSet<>();

            for (Map<String, Object> item : submitted) {
                Integer horizon = Integer.valueOf(
                        String.valueOf(item.get("horizon"))
                );

                if (!Set.of(1, 5, 20).contains(horizon)
                        || !horizons.add(horizon)) {
                    throw new IllegalStateException(
                            "Invalid prediction horizon"
                    );
                }

                Map<String, Object> row =
                        new LinkedHashMap<>();

                row.put(
                        "sessionTargetId",
                        sessionTargetId
                );
                row.put("horizon", horizon);
                row.put(
                        "direction",
                        item.get("direction")
                );

                // 예측 이유의 원문과 null 그대로 보존
                row.put("reason", item.get("reason"));

                predictions.add(row);
            }

            if (!horizons.equals(Set.of(1, 5, 20))) {
                throw new IllegalStateException(
                        "Expected all T1/T5/T20 predictions"
                );
            }
        }

        Map<String, Object> frozenNews =
                new LinkedHashMap<>();

        frozenNews.put("title", news.get("title"));
        frozenNews.put("briefing", news.get("briefing"));

        frozenNews.put(
                "facts",
                original.get("facts") == null
                        ? List.of()
                        : original.get("facts")
        );

        Map<String, Object> input =
                new LinkedHashMap<>();

        input.put(
                "stage",
                feedback.getFeedbackStage().getValue()
        );

        input.put(
                "sessionId",
                feedback.getLearningSessionId().toString()
        );

        input.put(
                "submittedAt",
                required(original, "inputCutoffAt")
        );

        input.put("news", frozenNews);
        input.put("predictions", predictions);
        input.put(
                "policyVersion",
                feedback.getPolicyVersion()
        );

        boolean isFinal =
                feedback.getFeedbackStage()
                        == AiFeedback.FeedbackStage.FINAL;

        if (isFinal) {
            if (initialJobId == null) {
                throw new IllegalStateException(
                        "initial_job_id_missing"
                );
            }

            input.put(
                    "outcomeCutoffAt",
                    snapshot.inputCutoffAt().toString()
            );

            input.put(
                    "baselines",
                    baselines(
                            snapshot.marketBaselineSnapshot()
                    )
            );

            input.put(
                    "results",
                    results(
                            snapshot.marketResultSnapshot()
                    )
            );

            input.put(
                    "reflections",
                    reflections(
                            snapshot.reflectionSnapshot()
                    )
            );
        }

        Map<String, Object> envelope =
                new LinkedHashMap<>();

        envelope.put(
                "beRequestId",
                feedback.getRequestId()
        );

        envelope.put(
                "feedbackId",
                feedback.getId().toString()
        );

        envelope.put("beInputHash", sha256(input));
        envelope.put("packageId", packageId);

        envelope.put(
                "newsId",
                required(news, "id")
        );

        envelope.put("bindings", bindings);
        envelope.put("input", input);

        if (isFinal) {
            envelope.put(
                    "initialJobId",
                    initialJobId.toString()
            );
        }

        return json(envelope);
    }

    private List<Map<String, Object>> baselines(
            Map<String, Object> snapshot
    ) {
        if (snapshot == null) {
            return List.of();
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Map<String, Object> row :
                objects(snapshot.get("targets"))) {

            Map<String, Object> mapped =
                    new LinkedHashMap<>(row);

            rows.add(mapped);
        }

        return rows;
    }

    private List<Map<String, Object>> results(
            Map<String, Object> snapshot
    ) {
        if (snapshot == null) {
            return List.of();
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Map<String, Object> target :
                objects(snapshot.get("targets"))) {

            String id =
                    required(target, "sessionTargetId");

            for (Map<String, Object> observation :
                    objects(target.get("observations"))) {

                Map<String, Object> mapped =
                        new LinkedHashMap<>(observation);

                mapped.put("sessionTargetId", id);

                if (mapped.containsKey("changeValue")) {
                    mapped.put(
                            "change",
                            mapped.remove("changeValue")
                    );
                }

                rows.add(mapped);
            }
        }

        return rows;
    }

    private List<Map<String, Object>> reflections(
            Map<String, Object> snapshot
    ) {
        if (snapshot == null) {
            return List.of();
        }

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (Map<String, Object> note :
                objects(snapshot.get("notes"))) {

            String originalType =
                    required(note, "reflectionType");

            Map<String, Object> row =
                    new LinkedHashMap<>();

            row.put(
                    "type",
                    "final".equals(originalType)
                            || "replay_final".equals(originalType)
                            ? "final"
                            : "intermediate"
            );

            row.put("body", note.get("body"));
            row.put("createdAt", note.get("createdAt"));

            if (note.get("sessionTargetId") != null) {
                row.put(
                        "sessionTargetId",
                        note.get("sessionTargetId")
                );
            }

            if ("day_1".equals(originalType)) {
                row.put("horizon", 1);
            }

            if ("day_5".equals(originalType)) {
                row.put("horizon", 5);
            }

            rows.add(row);
        }

        return rows;
    }

    private String sha256(Object value) {
        try {
            byte[] bytes = json(value).getBytes(
                    StandardCharsets.UTF_8
            );

            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
            );

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to hash AI business input",
                    ex
            );
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "AI JSON encoding failed",
                    ex
            );
        }
    }

    private static String required(
            Map<String, Object> map,
            String field
    ) {
        Object value = map.get(field);

        if (!(value instanceof String text)
                || text.isBlank()) {
            throw new IllegalStateException(
                    "Missing AI field: " + field
            );
        }

        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
            Object value
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "Expected AI snapshot object"
            );
        }

        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> objects(
            Object value
    ) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Expected AI snapshot array"
            );
        }

        List<Map<String, Object>> result =
                new ArrayList<>();

        for (Object element : list) {
            result.add(object(element));
        }

        return result;
    }
}