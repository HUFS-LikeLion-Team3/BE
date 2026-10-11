package com.finsight.feedback.client;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.service.FeedbackGenerationInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiFeedbackModelClient
        implements FeedbackModelClient {

    private static final String BASE_URL =
            "https://api.openai.com/v1";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public OpenAiFeedbackModelClient(
            @Value("${finsight.ai.openai.api-key:}")
            String apiKey,
            @Value("${finsight.ai.openai.model:gpt-5.6}")
            String model
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient =
                RestClient.builder()
                        .baseUrl(BASE_URL)
                        .build();
        this.jsonMapper =
                JsonMapper.builder().build();
    }

    @Override
    public Result generate(
            AiFeedback.FeedbackStage stage,
            FeedbackGenerationInput input
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured."
            );
        }

        try {
            String inputJson =
                    jsonMapper.writeValueAsString(
                            input.requestSnapshot()
                    );

            Map<String, Object> request =
                    new LinkedHashMap<>();

            request.put("model", model);

            request.put(
                    "input",
                    List.of(
                            Map.of(
                                    "role",
                                    "system",
                                    "content",
                                    systemPrompt(stage)
                            ),
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    """
                                    아래 JSON은 FinSight가 생성 시점에 고정한 입력 스냅샷입니다.
                                    JSON에 포함되지 않은 사실을 추가하지 마세요.

                                    %s
                                    """.formatted(inputJson)
                            )
                    )
            );

            request.put(
                    "text",
                    Map.of(
                            "format",
                            Map.of(
                                    "type",
                                    "json_schema",
                                    "name",
                                    "finsight_feedback",
                                    "strict",
                                    true,
                                    "schema",
                                    schema(stage)
                            )
                    )
            );

            request.put("store", false);

            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    restClient.post()
                            .uri("/responses")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + apiKey
                            )
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .body(request)
                            .retrieve()
                            .body(Map.class);

            if (response == null) {
                throw new IllegalStateException(
                        "OpenAI returned an empty response."
                );
            }

            String outputText =
                    extractOutputText(response);

            ModelOutput output =
                    jsonMapper.readValue(
                            outputText,
                            ModelOutput.class
                    );

            String returnedModel =
                    response.get("model") == null
                            ? model
                            : response.get("model").toString();

            List<Item> items =
                    output.items() == null
                            ? List.of()
                            : output.items()
                              .stream()
                              .map(item ->
                                   new Item(
                                           item.sessionTargetId(),
                                           item.type(),
                                           item.content(),
                                           item.variablesToCheck(),
                                           item.sortOrder(),
                                           item.referenceChunkIds()
                                   )
                              )
                              .toList();

            return new Result(
                    returnedModel,
                    output.flowSummary(),
                    output.insufficientEvidence(),
                    items
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI feedback generation failed.",
                    e
            );
        }
    }

    private String extractOutputText(
            Map<String, Object> response
    ) {
        Object outputObject = response.get("output");

        if (!(outputObject instanceof List<?> output)) {
            throw new IllegalStateException(
                    "OpenAI response does not contain output."
            );
        }

        for (Object outputItem : output) {
            if (!(outputItem instanceof Map<?, ?> item)) {
                continue;
            }

            Object contentObject = item.get("content");

            if (!(contentObject instanceof List<?> content)) {
                continue;
            }

            for (Object contentItem : content) {
                if (!(contentItem instanceof Map<?, ?> part)) {
                    continue;
                }

                if ("output_text".equals(
                        String.valueOf(part.get("type"))
                )) {
                    Object text = part.get("text");

                    if (text != null) {
                        return text.toString();
                    }
                }
            }
        }

        throw new IllegalStateException(
                "OpenAI response has no output_text."
        );
    }

    private String systemPrompt(
            AiFeedback.FeedbackStage stage
    ) {
        String stageRule =
                stage == AiFeedback.FeedbackStage.INITIAL
                        ? """
                        현재 단계는 initial이다.
                        예측 제출 시점 이후의 시장 결과를 추정하거나 암시하지 마라.
                        initial_uncertainty,
                        initial_counter_scenario,
                        initial_factor_to_review,
                        interpretation_limit만 사용할 수 있다.
                        """
                        : """
                        현재 단계는 final이다.
                        marketResult에서 usableForAiEvidence=true인 결과만
                        관찰 근거로 사용하라.
                        unavailable 또는 usableForAiEvidence=false인 값은
                        결과 판단의 증거로 사용하지 마라.
                        final_observed_flow,
                        final_co_observed_factors,
                        final_reflection_improvement,
                        final_next_hypothesis,
                        interpretation_limit만 사용할 수 있다.
                        """;

        return """
                당신은 FinSight의 경제 뉴스 학습 피드백 생성기다.

                사용자의 예측을 채점하지 마라.
                사용자를 평가하거나 점수를 매기지 마라.
                투자 조언이나 매수·매도 권유를 하지 마라.
                뉴스가 시장 움직임의 원인이라고 단정하지 마라.
                관찰된 흐름과 함께 관찰된 요인을 구분하라.

                반드시 제공된 JSON 자료만 사용한다.
                JSON에 없는 사실을 외부 지식으로 추가하지 않는다.

                근거가 부족하면 insufficientEvidence=true로 설정한다.
                근거가 부족한 경우 interpretation_limit 항목을 활용한다.

                referenceChunkIds에는 입력 JSON의 approvedSources.chunks에
                실제 존재하는 chunkId만 넣는다.
                출처가 필요 없는 일반적인 학습 피드백이면 빈 배열을 사용한다.

                sessionTargetId에는 입력 JSON prediction.targets의
                sessionTargetId만 사용하거나,
                세션 전체 피드백이면 null을 사용한다.

                flowSummary는 세션 전체 판단 흐름을 한 번만 요약한다.

                %s
                """.formatted(stageRule);
    }

    private Map<String, Object> schema(
            AiFeedback.FeedbackStage stage
    ) {
        List<String> allowedTypes =
                stage == AiFeedback.FeedbackStage.INITIAL
                        ? List.of(
                        "initial_uncertainty",
                        "initial_counter_scenario",
                        "initial_factor_to_review",
                        "interpretation_limit"
                )
                        : List.of(
                        "final_observed_flow",
                        "final_co_observed_factors",
                        "final_reflection_improvement",
                        "final_next_hypothesis",
                        "interpretation_limit"
                );

        Map<String, Object> targetIdSchema =
                Map.of(
                        "anyOf",
                        List.of(
                                Map.of("type", "string"),
                                Map.of("type", "null")
                        )
                );

        Map<String, Object> itemSchema =
                Map.of(
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "sessionTargetId",
                                targetIdSchema,
                                "type",
                                Map.of(
                                        "type",
                                        "string",
                                        "enum",
                                        allowedTypes
                                ),
                                "content",
                                Map.of(
                                        "type",
                                        "string"
                                ),
                                "variablesToCheck",
                                Map.of(
                                        "type",
                                        "array",
                                        "items",
                                        Map.of(
                                                "type",
                                                "string"
                                        )
                                ),
                                "sortOrder",
                                Map.of(
                                        "type",
                                        "integer"
                                ),
                                "referenceChunkIds",
                                Map.of(
                                        "type",
                                        "array",
                                        "items",
                                        Map.of(
                                                "type",
                                                "string"
                                        )
                                )
                        ),
                        "required",
                        List.of(
                                "sessionTargetId",
                                "type",
                                "content",
                                "variablesToCheck",
                                "sortOrder",
                                "referenceChunkIds"
                        )
                );

        return Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                Map.of(
                        "flowSummary",
                        Map.of(
                                "type",
                                "string"
                        ),
                        "insufficientEvidence",
                        Map.of(
                                "type",
                                "boolean"
                        ),
                        "items",
                        Map.of(
                                "type",
                                "array",
                                "items",
                                itemSchema
                        )
                ),
                "required",
                List.of(
                        "flowSummary",
                        "insufficientEvidence",
                        "items"
                )
        );
    }

    private record ModelOutput(
            String flowSummary,
            boolean insufficientEvidence,
            List<ModelItem> items
    ) {
    }

    private record ModelItem(
            String sessionTargetId,
            String type,
            String content,
            List<String> variablesToCheck,
            int sortOrder,
            List<String> referenceChunkIds
    ) {
    }
}