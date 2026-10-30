package com.finsight.feedback.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class AiRagJobClient {

    private static final String JOBS_PATH = "/internal/v1/jobs";

    private final String baseUrl;
    private final String bearerToken;
    private final HttpClient httpClient;
    private final JsonMapper mapper;

    @Autowired
    public AiRagJobClient(
            @Value("${AI_RAG_BASE_URL:}") String baseUrl,
            @Value("${AI_RAG_BEARER_TOKEN:}") String bearerToken
    ) {
        this(
                baseUrl,
                bearerToken,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                JsonMapper.builder().build()
        );
    }

    AiRagJobClient(
            String baseUrl,
            String bearerToken,
            HttpClient httpClient,
            JsonMapper mapper
    ) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.bearerToken = bearerToken == null
                ? "" : bearerToken.trim();
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public Map<String, Object> submitFrozenJob(String requestJson) {
        validateEnvelope(requestJson);
        return send("POST", JOBS_PATH, requestJson);
    }

    public Map<String, Object> getJobResource(String relativePath) {
        validateJobPath(relativePath);
        return send("GET", relativePath, null);
    }

    public Map<String, Object> findByBeRequestId(String beRequestId) {
        if (beRequestId == null || beRequestId.isBlank()) {
            throw new IllegalArgumentException(
                    "beRequestId is required"
            );
        }

        String encoded = URLEncoder.encode(
                beRequestId,
                StandardCharsets.UTF_8
        );

        return send(
                "GET",
                JOBS_PATH + "?beRequestId=" + encoded,
                null
        );
    }

    public Map<String, Object> getJobStatus(UUID jobId) {
        return send(
                "GET",
                JOBS_PATH + "/" + jobId,
                null
        );
    }

    public Map<String, Object> getApprovedResult(UUID jobId) {
        return send(
                "GET",
                JOBS_PATH + "/" + jobId + "/result",
                null
        );
    }

    private void validateEnvelope(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            throw new IllegalArgumentException(
                    "AI job request body is empty"
            );
        }

        Map<String, Object> envelope = parse(requestJson);

        requireString(envelope, "beRequestId");
        requireString(envelope, "feedbackId");
        requireString(envelope, "beInputHash");
        requireString(envelope, "packageId");
        requireString(envelope, "newsId");

        if (!(envelope.get("bindings") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "AI job bindings are missing"
            );
        }

        if (!(envelope.get("input") instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException(
                    "AI job input is missing"
            );
        }

        Object stage = input.get("stage");

        if (!"initial".equals(stage)
                && !"final".equals(stage)) {
            throw new IllegalArgumentException(
                    "Invalid AI feedback stage"
            );
        }

        if (!hasText(input.get("sessionId"))) {
            throw new IllegalArgumentException(
                    "AI input sessionId is missing"
            );
        }

        if (!hasText(input.get("submittedAt"))) {
            throw new IllegalArgumentException(
                    "AI input submittedAt is missing"
            );
        }

        if ("final".equals(stage)) {
            requireString(envelope, "initialJobId");
        }
    }

    private void requireString(
            Map<String, Object> envelope,
            String key
    ) {
        if (!hasText(envelope.get(key))) {
            throw new IllegalArgumentException(
                    "Required AI job field is missing: " + key
            );
        }
    }

    private boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private void validateJobPath(String path) {
        if (path == null
                || !path.startsWith(JOBS_PATH + "/")
                || path.contains("..")
                || path.contains("://")
                || path.contains("\\")) {
            throw new IllegalArgumentException(
                    "Invalid AI job resource path"
            );
        }
    }

    private Map<String, Object> send(
            String method,
            String path,
            String requestJson
    ) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "AI_RAG_BASE_URL is not configured"
            );
        }

        if (bearerToken.isBlank()) {
            throw new IllegalStateException(
                    "AI_RAG_BEARER_TOKEN is not configured"
            );
        }

        URI uri = URI.create(
                baseUrl.replaceAll("/+$", "") + path
        );

        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header(
                                "Authorization",
                                "Bearer " + bearerToken
                        )
                        .header("Accept", "application/json");

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");

            builder.POST(
                    HttpRequest.BodyPublishers.ofString(
                            requestJson,
                            StandardCharsets.UTF_8
                    )
            );
        } else {
            builder.GET();
        }

        try {
            HttpResponse<String> response =
                    httpClient.send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            int status = response.statusCode();

            if (status < 200 || status >= 300) {
                throw new AiRagHttpException(
                        status,
                        extractErrorCode(response.body())
                );
            }

            if (response.body() == null
                    || response.body().isBlank()) {
                throw new IllegalStateException(
                        "AI server returned an empty JSON response"
                );
            }

            return parse(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "AI job request interrupted",
                    e
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "AI server communication failed",
                    e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            Object value = mapper.readValue(
                    json,
                    Object.class
            );

            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "AI JSON must be an object"
                );
            }

            return (Map<String, Object>) value;

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid AI JSON document",
                    e
            );
        }
    }

    private String extractErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "unknown";
        }

        try {
            Map<String, Object> body = parse(responseBody);

            Object code = body.get("error");

            if (!(code instanceof String)) {
                code = body.get("code");
            }

            if (code instanceof String text && !text.isBlank()) {
                return text;
            }

            if (body.get("error") instanceof Map<?, ?> error) {
                Object nestedCode = error.get("code");

                if (nestedCode instanceof String text
                        && !text.isBlank()) {
                    return text;
                }
            }

        } catch (IllegalArgumentException ignored) {
            // 오류 응답 본문에 비밀 정보가 있어도 노출하지 않는다.
        }

        return "unknown";
    }

    public static class AiRagHttpException
            extends RuntimeException {

        private final int statusCode;
        private final String errorCode;

        public AiRagHttpException(
                int statusCode,
                String errorCode
        ) {
            super(
                    "AI/RAG request failed: HTTP "
                            + statusCode
                            + ", code="
                            + errorCode
            );

            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}