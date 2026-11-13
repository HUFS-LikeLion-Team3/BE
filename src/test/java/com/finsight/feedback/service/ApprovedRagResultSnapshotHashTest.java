
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovedRagResultSnapshotHashTest {

    private static final String VALID_RAG_HASH = "a".repeat(64);
    private static final String OTHER_RAG_HASH = "b".repeat(64);

    private final JsonMapper json = JsonMapper.builder().build();
    private final ApprovedRagResultParser parser =
            new ApprovedRagResultParser();

    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID marketTargetId = UUID.randomUUID();

    private final String requestId =
            "feedback:initial:" + sessionId + ":v1";

    private final String packageId = "sha256:" + "c".repeat(64);

    private record Fixture(AiRagJob job, AiFeedback feedback) { }

    @Test
    void matchingRagHashAllowsApprovedV3Result() {
        Fixture fixture = fixture(VALID_RAG_HASH, VALID_RAG_HASH);

        ApprovedRagResultParser.Parsed result =
                assertDoesNotThrow(() -> parser.parse(
                        fixture.job(), fixture.feedback()
                ));

        assertEquals("news-market-judgment-v3.2", result.promptVersion());
        assertEquals("summary", result.flowSummary());
        assertEquals(5, result.items().size());
    }

    @Test
    void differentRagHashIsRejected() {
        Fixture fixture = fixture(VALID_RAG_HASH, OTHER_RAG_HASH);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(fixture.job(), fixture.feedback())
        );

        assertEquals(
                "approved_result_rag_snapshot_hash_mismatch",
                ex.getMessage()
        );
    }

    @Test
    void missingStoredRagHashIsRejected() {
        Fixture fixture = fixture(null, VALID_RAG_HASH);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(fixture.job(), fixture.feedback())
        );

        assertEquals(
                "approved_result_rag_snapshot_hash_missing_or_invalid",
                ex.getMessage()
        );
    }

    @Test
    void missingResponseRagHashIsRejected() {
        Fixture fixture = fixture(VALID_RAG_HASH, null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(fixture.job(), fixture.feedback())
        );

        assertEquals(
                "approved_result_rag_snapshot_hash_missing_or_invalid",
                ex.getMessage()
        );
    }

    @Test
    void invalidResponseRagHashIsRejected() {
        Fixture fixture = fixture(VALID_RAG_HASH, "invalid");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(fixture.job(), fixture.feedback())
        );

        assertEquals(
                "approved_result_rag_snapshot_hash_missing_or_invalid",
                ex.getMessage()
        );
    }

    private Fixture fixture(String storedHash, String responseHash) {
        Map<String, Object> output = Map.of(
                "stage", "initial",
                "insufficientEvidence", true,
                "sessionItems", List.of(
                        item("flow_summary", "summary"),
                        item("news_analysis", "analysis")
                ),
                "targetFeedbacks", List.of(
                        Map.of(
                                "sessionTargetId", targetId.toString(),
                                "items", List.of(
                                        item("initial_market_impact", "impact"),
                                        item("initial_prediction_review", "review"),
                                        item("initial_counter_scenario", "scenario")
                                )
                        )
                ),
                "references", List.of()
        );

        String resultHash = sha256(json.writeValueAsString(sorted(output)));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ready");
        response.put("feedbackId", feedbackId.toString());
        response.put("sessionId", sessionId.toString());
        response.put("jobId", jobId.toString());
        response.put("stage", "initial");
        response.put("beRequestId", requestId);
        response.put("packageId", packageId);
        response.put("beInputHash", "d".repeat(64));
        response.put("resultHash", resultHash);
        response.put("modelVersion", "local-demo");
        response.put("promptVersion", "news-market-judgment-v3.2");
        response.put("policyVersion", "feedback-policy-p0-v1");
        response.put("generatedAt", "2026-11-09T10:00:00+09:00");
        response.put("output", output);

        if (responseHash != null) {
            response.put("ragSnapshotHash", responseHash);
        }

        Map<String, Object> request = Map.of(
                "feedbackId", feedbackId.toString(),
                "beRequestId", requestId,
                "packageId", packageId,
                "beInputHash", "d".repeat(64),
                "bindings", Map.of(
                        targetId.toString(),
                        marketTargetId.toString()
                )
        );

        AiRagJob job = mock(AiRagJob.class);
        when(job.getApprovedResultJson())
                .thenReturn(json.writeValueAsString(response));
        when(job.getRequestJson())
                .thenReturn(json.writeValueAsString(request));
        when(job.getResultHash()).thenReturn(resultHash);
        when(job.getRagSnapshotHash()).thenReturn(storedHash);
        when(job.getFeedbackId()).thenReturn(feedbackId);
        when(job.getSessionId()).thenReturn(sessionId);
        when(job.getJobId()).thenReturn(jobId);
        when(job.getStage()).thenReturn("initial");
        when(job.getBeRequestId()).thenReturn(requestId);
        when(job.getPackageId()).thenReturn(packageId);

        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getId()).thenReturn(feedbackId);
        when(feedback.getLearningSessionId()).thenReturn(sessionId);
        when(feedback.getRequestId()).thenReturn(requestId);
        when(feedback.getFeedbackStage())
                .thenReturn(AiFeedback.FeedbackStage.INITIAL);

        return new Fixture(job, feedback);
    }

    private Map<String, Object> item(String type, String content) {
        return Map.of(
                "type", type,
                "content", content,
                "referenceIds", List.of()
        );
    }

    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, val) ->
                    ordered.put((String) key, sorted(val))
            );
            return ordered;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(this::sorted).toList();
        }

        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
