
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackItemRulesVersionTest {

    private static final List<String> MODERN_VERSIONS =
            List.of(
                    "news-market-explanation-v2.1",
                    "news-market-judgment-v3",
                    "news-market-judgment-v3.1",
                    "news-market-judgment-v3.2"
            );

    @Test
    void supportedVersionsUseModernItemContract() {
        for (String version : MODERN_VERSIONS) {
            assertTrue(
                    FeedbackItemRules.usesV2ItemContract(version),
                    version
            );
        }
    }

    @Test
    void unknownAndLegacyVersionsAreNotModern() {
        assertFalse(
                FeedbackItemRules.usesV2ItemContract(null)
        );

        assertFalse(
                FeedbackItemRules.usesV2ItemContract(
                        "feedback-p0-v1"
                )
        );

        assertFalse(
                FeedbackItemRules.usesV2ItemContract(
                        "news-market-judgment-v3.3"
                )
        );

        assertFalse(
                FeedbackItemRules.usesV2ItemContract(
                        "unknown-version"
                )
        );
    }

    @Test
    void allModernVersionsRequireInitialNewsAnalysis() {
        for (String version : MODERN_VERSIONS) {
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () -> FeedbackItemRules.validateRequiredTypes(
                                    AiFeedback.FeedbackStage.INITIAL,
                                    version,
                                    Set.of(),
                                    Map.of(
                                            "session",
                                            Set.of("flow_summary")
                                    )
                            ),
                            version
                    );

            assertTrue(
                    exception.getMessage()
                            .contains("news_analysis"),
                    version
            );
        }
    }

    @Test
    void allModernVersionsAcceptValidInitialItems() {
        UUID targetId = UUID.randomUUID();

        Map<String, Set<String>> items = Map.of(
                "session",
                Set.of(
                        "flow_summary",
                        "news_analysis"
                ),
                targetId.toString(),
                Set.of(
                        "initial_market_impact",
                        "initial_prediction_review",
                        "initial_counter_scenario"
                )
        );

        for (String version : MODERN_VERSIONS) {
            assertDoesNotThrow(
                    () -> FeedbackItemRules.validateRequiredTypes(
                            AiFeedback.FeedbackStage.INITIAL,
                            version,
                            Set.of(targetId),
                            items
                    ),
                    version
            );
        }
    }

    @Test
    void allModernVersionsAcceptValidFinalItems() {
        UUID targetId = UUID.randomUUID();

        Map<String, Set<String>> items = Map.of(
                "session",
                Set.of("flow_summary"),
                targetId.toString(),
                Set.of(
                        "final_observed_flow",
                        "final_market_explanation",
                        "final_prediction_review"
                )
        );

        for (String version : MODERN_VERSIONS) {
            assertDoesNotThrow(
                    () -> FeedbackItemRules.validateRequiredTypes(
                            AiFeedback.FeedbackStage.FINAL,
                            version,
                            Set.of(targetId),
                            items
                    ),
                    version
            );
        }
    }

    @Test
    void modernFinalRequiresPredictionReview() {
        UUID targetId = UUID.randomUUID();

        for (String version : MODERN_VERSIONS) {
            assertThrows(
                    IllegalStateException.class,
                    () -> FeedbackItemRules.validateRequiredTypes(
                            AiFeedback.FeedbackStage.FINAL,
                            version,
                            Set.of(targetId),
                            Map.of(
                                    "session",
                                    Set.of("flow_summary"),
                                    targetId.toString(),
                                    Set.of(
                                            "final_observed_flow",
                                            "final_market_explanation"
                                    )
                            )
                    ),
                    version
            );
        }
    }
}
