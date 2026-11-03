
package com.finsight.feedback.service;

import com.finsight.feedback.client.FeedbackModelClient;
import com.finsight.feedback.entity.AiFeedback;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FeedbackItemRules {

    public static final String V2_PROMPT_VERSION =
            "news-market-explanation-v2.1";

    private static final List<String> INITIAL_V2_SESSION =
            List.of(
                    "flow_summary",
                    "news_analysis",
                    "interpretation_limit"
            );

    private static final List<String> INITIAL_V2_TARGET =
            List.of(
                    "initial_market_impact",
                    "initial_prediction_review",
                    "initial_counter_scenario",
                    "interpretation_limit"
            );

    private static final List<String> FINAL_V2_SESSION =
            List.of(
                    "flow_summary",
                    "interpretation_limit"
            );

    private static final List<String> FINAL_V2_TARGET =
            List.of(
                    "final_observed_flow",
                    "final_market_explanation",
                    "final_prediction_review",
                    "final_takeaway",
                    "interpretation_limit"
            );

    private static final Set<String> INITIAL_LEGACY_TARGET = Set.of(
            "initial_uncertainty",
            "initial_counter_scenario",
            "initial_factor_to_review",
            "interpretation_limit"
    );

    private static final Set<String> FINAL_LEGACY_TARGET = Set.of(
            "final_observed_flow",
            "final_co_observed_factors",
            "final_reflection_improvement",
            "final_next_hypothesis",
            "interpretation_limit"
    );

    private static final Set<String> LEGACY_SESSION =
            Set.of("flow_summary", "interpretation_limit");

    private FeedbackItemRules() {
    }

    public static void validate(
            AiFeedback.FeedbackStage stage,
            String promptVersion,
            FeedbackGenerationInput input,
            List<FeedbackModelClient.Item> items
    ) {
        if (stage == null || input == null || items == null) {
            throw new IllegalArgumentException(
                    "Feedback item validation input is missing."
            );
        }

        boolean v2 = V2_PROMPT_VERSION.equals(promptVersion);

        Map<String, Set<Integer>> sortOrdersByScope =
                new HashMap<>();

        Map<String, Integer> lastOrderByScope =
                new HashMap<>();

        Map<String, Set<String>> typesByScope =
                new HashMap<>();

        Set<UUID> allowedTargets =
                input.allowedTargetIds() == null
                        ? Set.of()
                        : input.allowedTargetIds();

        for (FeedbackModelClient.Item item : items) {
            if (item == null
                    || item.type() == null
                    || item.content() == null
                    || item.content().isBlank()) {
                throw new IllegalStateException(
                        "AI feedback item body or type is empty."
                );
            }

            if (v2 && item.sortOrder() < 0) {
                throw new IllegalStateException(
                        "Invalid feedback sort order."
                );
            }

            boolean sessionItem =
                    item.sessionTargetId() == null;

            UUID targetId = null;

            if (!sessionItem) {
                targetId = parseUuid(
                        item.sessionTargetId(),
                        "session target"
                );

                if (!allowedTargets.contains(targetId)) {
                    throw new IllegalStateException(
                            "AI returned an unknown session target."
                    );
                }
            }

            String scope =
                    sessionItem ? "session" : targetId.toString();

            if (!sortOrdersByScope
                    .computeIfAbsent(
                            scope,
                            key -> new HashSet<>()
                    )
                    .add(item.sortOrder())) {
                throw new IllegalStateException(
                        "Duplicate feedback sort order in scope."
                );
            }

            List<String> v2Types =
                    stage == AiFeedback.FeedbackStage.INITIAL
                            ? (sessionItem
                               ? INITIAL_V2_SESSION
                               : INITIAL_V2_TARGET)
                            : (sessionItem
                               ? FINAL_V2_SESSION
                               : FINAL_V2_TARGET);

            Set<String> legacyTypes =
                    sessionItem
                            ? LEGACY_SESSION
                            : stage == AiFeedback.FeedbackStage.INITIAL
                              ? INITIAL_LEGACY_TARGET
                              : FINAL_LEGACY_TARGET;

            if (v2
                    ? !v2Types.contains(item.type())
                    : !legacyTypes.contains(item.type())) {
                throw new IllegalStateException(
                        "Invalid feedback item type or scope: "
                                + item.type()
                );
            }

            Set<String> scopeTypes =
                    typesByScope.computeIfAbsent(
                            scope,
                            key -> new HashSet<>()
                    );

            if (!scopeTypes.add(item.type())) {
                throw new IllegalStateException(
                        "Duplicate feedback item type in scope: "
                                + item.type()
                );
            }

            if (v2) {
                int rank = v2Types.indexOf(item.type());
                int previous =
                        lastOrderByScope.getOrDefault(scope, -1);

                if (rank <= previous) {
                    throw new IllegalStateException(
                            "Invalid v2 feedback item order: "
                                    + item.type()
                    );
                }

                lastOrderByScope.put(scope, rank);
            }

            if (item.referenceChunkIds() != null) {
                for (String rawId : item.referenceChunkIds()) {
                    UUID chunkId = parseUuid(
                            rawId,
                            "reference chunk"
                    );

                    if (input.evidenceChunks() == null
                            || !input.evidenceChunks()
                            .containsKey(chunkId)) {
                        throw new IllegalStateException(
                                "AI returned an unknown reference chunk."
                        );
                    }
                }
            }
        }

        if (v2) {
            Set<String> sessionRequired =
                    stage == AiFeedback.FeedbackStage.INITIAL
                            ? Set.of(
                            "flow_summary",
                            "news_analysis"
                    )
                            : Set.of("flow_summary");

            checkRequired(
                    "session",
                    sessionRequired,
                    typesByScope
            );

            Set<String> targetRequired =
                    stage == AiFeedback.FeedbackStage.INITIAL
                            ? Set.of(
                            "initial_market_impact",
                            "initial_prediction_review",
                            "initial_counter_scenario"
                    )
                            : Set.of(
                            "final_observed_flow",
                            "final_market_explanation",
                            "final_prediction_review"
                    );

            for (UUID allowedTargetId : allowedTargets) {
                checkRequired(
                        allowedTargetId.toString(),
                        targetRequired,
                        typesByScope
                );
            }
        }
    }

    private static UUID parseUuid(
            String raw,
            String label
    ) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid " + label + " UUID: " + raw,
                    exception
            );
        }
    }

    private static void checkRequired(
            String scope,
            Set<String> required,
            Map<String, Set<String>> typesByScope
    ) {
        Set<String> actual =
                typesByScope.getOrDefault(scope, Set.of());

        if (!actual.containsAll(required)) {
            Set<String> missing =
                    new HashSet<>(required);

            missing.removeAll(actual);

            throw new IllegalStateException(
                    "Missing v2 feedback items for "
                            + scope + ": " + missing
            );
        }
    }
}
