package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovedRagResultParserTest {
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID sessionTargetId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();
    private final String beRequestId = "feedback:initial:" + sessionId + ":v1";
    private final String packageId = "sha256:" + "a".repeat(64);
    private final String inputHash = "b".repeat(64);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ApprovedRagResultParser parser = new ApprovedRagResultParser();

    private Map<String, Object> item(String id, String type, int order, List<String> refs) {
        return Map.of("id", id, "type", type, "content", "한글 본문", "sortOrder", order,
                "referenceIds", refs);
    }

    private Map<String, Object> output() {
        Map<String, Object> a = Map.of("id", "R-A", "sourceDocumentId", documentId.toString(),
                "chunkId", chunkId.toString(), "citationExcerpt", "A".repeat(510) + "TAIL_A",
                "usageContext", "news_analysis");
        Map<String, Object> b = Map.of("id", "R-B", "sourceDocumentId", documentId.toString(),
                "chunkId", chunkId.toString(), "citationExcerpt", "different excerpt",
                "usageContext", "initial_market_impact");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", "initial");
        out.put("insufficientEvidence", false);
        out.put("sessionItems", List.of(
                item("session-1", "flow_summary", 0, List.of()),
                item("session-2", "news_analysis", 1, List.of("R-A", "R-B"))));
        out.put("targetFeedbacks", List.of(Map.of(
                "sessionTargetId", sessionTargetId.toString(),
                "items", List.of(item("target-1", "initial_market_impact", 0, List.of("R-B"))))));
        out.put("references", List.of(a, b));
        return out;
    }

    private Map<String, Object> response(Map<String, Object> output) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId.toString());
        response.put("feedbackId", feedbackId.toString());
        response.put("sessionId", sessionId.toString());
        response.put("stage", "initial");
        response.put("beRequestId", beRequestId);
        response.put("packageId", packageId);
        response.put("beInputHash", inputHash);
        response.put("status", "ready");
        response.put("modelVersion", "local-demo");
        response.put("promptVersion", FeedbackItemRules.V2_PROMPT_VERSION);
        response.put("policyVersion", "feedback-policy-p0-v1");
        response.put("generatedAt", "2026-11-03T10:00:00+09:00");
        response.put("resultHash", digest(output));
        response.put("output", output);
        return response;
    }

    private String digest(Object output) {
        try {
            String value = mapper.writeValueAsString(sorted(output));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private Object sorted(Object input) {
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((key, value) -> out.put((String) key, sorted(value)));
            return out;
        }
        if (input instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return input;
    }

    private AiRagJob job(Map<String, Object> response) {
        AiRagJob job = mock(AiRagJob.class);
        when(job.getFeedbackId()).thenReturn(feedbackId);
        when(job.getSessionId()).thenReturn(sessionId);
        when(job.getBeRequestId()).thenReturn(beRequestId);
        when(job.getStage()).thenReturn("initial");
        when(job.getJobId()).thenReturn(jobId);
        when(job.getPackageId()).thenReturn(packageId);
        when(job.getResultHash()).thenReturn((String) response.get("resultHash"));
        when(job.getApprovedResultJson()).thenReturn(mapper.writeValueAsString(response));
        when(job.getRequestJson()).thenReturn(mapper.writeValueAsString(Map.of(
                "feedbackId", feedbackId.toString(),
                "beRequestId", beRequestId,
                "packageId", packageId,
                "beInputHash", inputHash,
                "bindings", Map.of(sessionTargetId.toString(), UUID.randomUUID().toString()))));
        return job;
    }

    private AiFeedback feedback() {
        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getId()).thenReturn(feedbackId);
        when(feedback.getLearningSessionId()).thenReturn(sessionId);
        when(feedback.getFeedbackStage()).thenReturn(AiFeedback.FeedbackStage.INITIAL);
        when(feedback.getRequestId()).thenReturn(beRequestId);
        return feedback;
    }

    @Test
    void twoDifferentCitationsForOneChunkRemainDistinctAndLongExcerptSurvives() {
        Map<String, Object> response = response(output());
        ApprovedRagResultParser.Parsed parsed = parser.parse(job(response), feedback());
        assertEquals(3, parsed.items().size());
        assertEquals(2, parsed.citations().size());
        assertEquals(chunkId, parsed.citations().get(0).chunkId());
        assertEquals(chunkId, parsed.citations().get(1).chunkId());
        assertNotEquals(parsed.citations().get(0).referenceId(), parsed.citations().get(1).referenceId());
        assertTrue(parsed.citations().get(0).citationExcerpt().endsWith("TAIL_A"));
        assertTrue(parsed.citations().get(0).citationExcerpt().length() > 500);
        assertEquals("initial_market_impact", parsed.citations().get(1).usageContext());
        assertEquals(List.of("R-A", "R-B"), parsed.items().get(1).referenceIds());
        assertEquals(List.of("R-B"), parsed.items().get(2).referenceIds());
        assertEquals(OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), parsed.generatedAt());
    }

    @Test
    void approvedDemoV1ItemsWithoutIdsOrSortOrdersKeepOrderTypesAndCitations() {
        Map<String, Object> body = output();
        body.put("sessionItems", List.of(
                Map.of("type", "flow_summary", "content", "데모 요약", "referenceIds", List.of()),
                Map.of("type", "interpretation_limit", "content", "데모 한계", "referenceIds", List.of("R-A", "R-B"))));
        body.put("targetFeedbacks", List.of(Map.of(
                "sessionTargetId", sessionTargetId.toString(),
                "items", List.of(
                        Map.of("type", "initial_uncertainty", "content", "불확실성", "referenceIds", List.of()),
                        Map.of("type", "initial_counter_scenario", "content", "반대 시나리오", "referenceIds", List.of("R-B")),
                        Map.of("type", "initial_factor_to_review", "content", "검토 변수", "referenceIds", List.of())))));
        Map<String, Object> response = response(body);
        response.put("promptVersion", "feedback-integration-v1");
        ApprovedRagResultParser.Parsed parsed = parser.parse(job(response), feedback());
        assertEquals(5, parsed.items().size());
        assertEquals(List.of(0, 1, 0, 1, 2),
                parsed.items().stream().map(ApprovedRagResultParser.Item::sourceSortOrder).toList());
        assertEquals(List.of("flow_summary", "interpretation_limit", "initial_uncertainty",
                "initial_counter_scenario", "initial_factor_to_review"),
                parsed.items().stream().map(item -> item.type().name()).toList());
        assertEquals(List.of("R-A", "R-B"), parsed.items().get(1).referenceIds());
        assertEquals(2, parsed.citations().size());
        assertEquals(chunkId, parsed.citations().get(0).chunkId());
        assertEquals(chunkId, parsed.citations().get(1).chunkId());
        assertNotEquals(parsed.citations().get(0).referenceId(), parsed.citations().get(1).referenceId());
    }

    @Test
    void explicitNullItemIdIsRejectedEvenWhenIdIsOptional() {
        Map<String, Object> body = output();
        Map<String, Object> broken = new LinkedHashMap<>(Map.of("id", "session-1", "type", "flow_summary",
                "content", "한글 본문", "sortOrder", 0, "referenceIds", List.of()));
        broken.put("id", null);
        body.put("sessionItems", List.of(broken, ((List<?>)body.get("sessionItems")).get(1)));
        Map<String, Object> response = response(body);
        assertThrows(IllegalStateException.class, () -> parser.parse(job(response), feedback()));
    }

    @Test
    void changedContentWithStaleResultHashCannotBeStored() {
        Map<String, Object> body = output();
        Map<String, Object> response = response(body);
        body.put("insufficientEvidence", true);
        assertThrows(IllegalStateException.class, () -> parser.parse(job(response), feedback()));
    }

    @Test
    void wrongFeedbackIdCannotBeStored() {
        Map<String, Object> response = response(output());
        response.put("feedbackId", UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class, () -> parser.parse(job(response), feedback()));
    }

    @Test
    void unknownReferenceCannotBeLinked() {
        Map<String, Object> output = output();
        output.put("sessionItems", List.of(
                item("session-1", "flow_summary", 0, List.of("MISSING"))));
        Map<String, Object> response = response(output);
        assertThrows(IllegalStateException.class, () -> parser.parse(job(response), feedback()));
    }
}
