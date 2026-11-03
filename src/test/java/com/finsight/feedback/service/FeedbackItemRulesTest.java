
package com.finsight.feedback.service;

import com.finsight.feedback.client.FeedbackModelClient;
import com.finsight.feedback.entity.AiFeedback;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedbackItemRulesTest {

    private final UUID targetId = UUID.randomUUID();

    private FeedbackGenerationInput input() {
        return new FeedbackGenerationInput(
                OffsetDateTime.now(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                "hash",
                Set.of(targetId),
                Map.of()
        );
    }

    private FeedbackModelClient.Item session(String type, int order) {
        return new FeedbackModelClient.Item(
                null,
                type,
                "테스트 본문",
                List.of(),
                order,
                List.of()
        );
    }

    private FeedbackModelClient.Item target(String type, int order) {
        return new FeedbackModelClient.Item(
                targetId.toString(),
                type,
                "테스트 본문",
                List.of(),
                order,
                List.of()
        );
    }

    @Test
    void v2InitialItemsAreValid() {
        List<FeedbackModelClient.Item> items = List.of(
                session("flow_summary", 1),
                session("news_analysis", 2),
                target("initial_market_impact", 1),
                target("initial_prediction_review", 2),
                target("initial_counter_scenario", 3)
        );

        assertDoesNotThrow(() ->
                FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        FeedbackItemRules.V2_PROMPT_VERSION,
                        input(),
                        items
                )
        );
    }

    @Test
    void v2FinalTakeawayIsOptional() {
        List<FeedbackModelClient.Item> required = List.of(
                session("flow_summary", 1),
                target("final_observed_flow", 1),
                target("final_market_explanation", 2),
                target("final_prediction_review", 3)
        );

        assertDoesNotThrow(() ->
                FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.FINAL,
                        FeedbackItemRules.V2_PROMPT_VERSION,
                        input(),
                        required
                )
        );

        List<FeedbackModelClient.Item> withTakeaway =
                new ArrayList<>(required);

        withTakeaway.add(target("final_takeaway", 4));

        assertDoesNotThrow(() ->
                FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.FINAL,
                        FeedbackItemRules.V2_PROMPT_VERSION,
                        input(),
                        withTakeaway
                )
        );
    }

    @Test
    void v2RejectsMissingRequiredItem() {
        assertThrows(
                IllegalStateException.class,
                () -> FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.FINAL,
                        FeedbackItemRules.V2_PROMPT_VERSION,
                        input(),
                        List.of(
                                session("flow_summary", 1),
                                target("final_observed_flow", 1),
                                target("final_market_explanation", 2)
                        )
                )
        );
    }

    @Test
    void v2RejectsWrongOrder() {
        assertThrows(
                IllegalStateException.class,
                () -> FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        FeedbackItemRules.V2_PROMPT_VERSION,
                        input(),
                        List.of(
                                session("news_analysis", 1),
                                session("flow_summary", 2)
                        )
                )
        );
    }

    @Test
    void v1AllowsLegacyTypes() {
        assertDoesNotThrow(() ->
                FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        "feedback-p0-v1",
                        input(),
                        List.of(
                                target("initial_uncertainty", 1),
                                target("initial_counter_scenario", 2),
                                session("interpretation_limit", 1)
                        )
                )
        );
    }

    @Test
    void v1RejectsNewV2Types() {
        assertThrows(
                IllegalStateException.class,
                () -> FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        "feedback-p0-v1",
                        input(),
                        List.of(
                                session("news_analysis", 1)
                        )
                )
        );
    }

    @Test
    void duplicateOrderInSameScopeIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        "feedback-p0-v1",
                        input(),
                        List.of(
                                target("initial_uncertainty", 1),
                                target("initial_counter_scenario", 1)
                        )
                )
        );
    }

    @Test
    void sameOrderAcrossDifferentScopesIsAllowed() {
        assertDoesNotThrow(() ->
                FeedbackItemRules.validate(
                        AiFeedback.FeedbackStage.INITIAL,
                        "feedback-p0-v1",
                        input(),
                        List.of(
                                session("flow_summary", 1),
                                target("initial_uncertainty", 1)
                        )
                )
        );
    }
}
