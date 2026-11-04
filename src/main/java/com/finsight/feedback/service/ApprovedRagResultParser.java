package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiRagJob;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Converts the *captured*, approved AI response into a BE write plan. This class
 * never invents a citation, changes an excerpt, or writes a row itself.
 */
@Component
public class ApprovedRagResultParser {

    private final JsonMapper json = JsonMapper.builder().build();

    public record Item(UUID sessionTargetId, AiFeedbackItem.FeedbackItemType type,
                       String content, int sourceSortOrder, List<String> referenceIds) { }

    public record Parsed(String modelVersion, String promptVersion,
                         String policyVersion, OffsetDateTime generatedAt,
                         String flowSummary, boolean insufficientEvidence,
                         List<Item> items,
                         List<ApprovedCitationWriter.Citation> citations) { }

    public Parsed parse(AiRagJob job, AiFeedback feedback) {
        if (job == null || feedback == null || job.getApprovedResultJson() == null
                || job.getResultHash() == null) {
            throw new IllegalStateException("approved_result_not_captured");
        }
        Map<String, Object> response = parseJson(job.getApprovedResultJson());
        Map<String, Object> request = parseJson(job.getRequestJson());

        if (!feedback.getId().equals(job.getFeedbackId())
                || !feedback.getLearningSessionId().equals(job.getSessionId())
                || !feedback.getRequestId().equals(job.getBeRequestId())
                || !feedback.getFeedbackStage().getValue().equals(job.getStage())) {
            throw new IllegalStateException("approved_result_feedback_job_mismatch");
        }
        expect(response, "feedbackId", job.getFeedbackId().toString());
        expect(response, "sessionId", job.getSessionId().toString());
        expect(response, "stage", job.getStage());
        expect(response, "beRequestId", job.getBeRequestId());
        expect(response, "jobId", job.getJobId().toString());
        expect(response, "packageId", job.getPackageId());
        expect(response, "beInputHash", requiredString(request, "beInputHash"));
        expect(request, "feedbackId", job.getFeedbackId().toString());
        expect(request, "beRequestId", job.getBeRequestId());
        expect(request, "packageId", job.getPackageId());

        if (!"ready".equals(response.get("status"))) {
            throw new IllegalStateException("approved_result_is_not_ready");
        }
        String resultHash = requiredString(response, "resultHash");
        if (!resultHash.matches("[0-9a-f]{64}")
                || !resultHash.equals(job.getResultHash())) {
            throw new IllegalStateException("approved_result_hash_mismatch");
        }

        Map<String, Object> output = object(response.get("output"), "output");
        if (!resultHash.equals(sha256(canonicalJson(output)))) {
            throw new IllegalStateException("approved_result_output_hash_mismatch");
        }
        expect(output, "stage", job.getStage());
        if (!(output.get("insufficientEvidence") instanceof Boolean insufficient)) {
            throw new IllegalStateException("approved_output_insufficientEvidence_missing");
        }
        String modelVersion = requiredString(response, "modelVersion");
        String promptVersion = requiredString(response, "promptVersion");
        String policyVersion = requiredString(response, "policyVersion");
        OffsetDateTime generatedAt = OffsetDateTime.parse(requiredString(response, "generatedAt"));

        Map<String, Object> bindings = object(request.get("bindings"), "bindings");
        if (bindings.isEmpty() || bindings.size() > 3) {
            throw new IllegalStateException("approved_bindings_invalid");
        }
        Set<UUID> allowedTargets = new HashSet<>();
        for (String target : bindings.keySet()) {
            allowedTargets.add(uuid(target, "sessionTargetId"));
        }

        List<Item> items = new ArrayList<>();
        Set<String> seenAiItemIds = new HashSet<>();
        readItems(array(output.get("sessionItems"), "sessionItems"), null,
                seenAiItemIds, items);
        Set<UUID> seenTargets = new HashSet<>();
        for (Map<String, Object> target : objects(output.get("targetFeedbacks"), "targetFeedbacks")) {
            UUID targetId = uuid(requiredString(target, "sessionTargetId"), "sessionTargetId");
            if (!allowedTargets.contains(targetId) || !seenTargets.add(targetId)) {
                throw new IllegalStateException("approved_result_unknown_or_duplicate_target");
            }
            readItems(array(target.get("items"), "targetFeedbacks.items"), targetId,
                    seenAiItemIds, items);
        }
        if (!seenTargets.equals(allowedTargets)) {
            throw new IllegalStateException("approved_result_missing_target");
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("approved_result_items_empty");
        }
        validateStageScopes(job.getStage(), promptVersion, items, allowedTargets);

        List<ApprovedCitationWriter.Citation> citations = new ArrayList<>();
        Set<String> referenceIds = new HashSet<>();
        for (Map<String, Object> row : objects(output.get("references"), "references")) {
            String id = alias(row, "id", "referenceId");
            UUID documentId = uuid(requiredString(row, "sourceDocumentId"), "sourceDocumentId");
            UUID chunkId = uuid(alias(row, "chunkId", "sourceDocumentChunkId"), "chunkId");
            String excerpt = requiredString(row, "citationExcerpt");
            if (!row.containsKey("usageContext")
                    || (row.get("usageContext") != null
                    && !(row.get("usageContext") instanceof String))) {
                throw new IllegalStateException("approved_reference_usage_context_invalid");
            }
            if (!referenceIds.add(id)) {
                throw new IllegalStateException("approved_reference_duplicate_id");
            }
            citations.add(new ApprovedCitationWriter.Citation(id, documentId, chunkId,
                    excerpt, (String) row.get("usageContext")));
        }
        for (Item item : items) {
            for (String id : item.referenceIds()) {
                if (!referenceIds.contains(id)) {
                    throw new IllegalStateException("approved_item_unknown_reference");
                }
            }
        }

        String summary = items.stream()
                .filter(i -> i.sessionTargetId() == null
                        && i.type() == AiFeedbackItem.FeedbackItemType.flow_summary)
                .map(Item::content).findFirst()
                .orElseThrow(() -> new IllegalStateException("approved_flow_summary_missing"));
        // The legacy field is derived from the actual approved session item.
        if (output.containsKey("flowSummary") && output.get("flowSummary") != null
                && !summary.equals(output.get("flowSummary"))) {
            throw new IllegalStateException("approved_flow_summary_conflict");
        }
        return new Parsed(modelVersion, promptVersion, policyVersion, generatedAt,
                summary, insufficient, List.copyOf(items), List.copyOf(citations));
    }

    private void validateStageScopes(String stage, String promptVersion, List<Item> items,
                                     Set<UUID> targets) {
        // v2 responses use the newer type taxonomy. The approved demo/v1 output
        // uses legacy types and does not contain item IDs or sortOrder fields.
        boolean v2 = FeedbackItemRules.V2_PROMPT_VERSION.equals(promptVersion);
        Set<AiFeedbackItem.FeedbackItemType> sessionTypes = v2 && "initial".equals(stage)
                ? Set.of(AiFeedbackItem.FeedbackItemType.flow_summary,
                        AiFeedbackItem.FeedbackItemType.news_analysis,
                        AiFeedbackItem.FeedbackItemType.interpretation_limit)
                : Set.of(AiFeedbackItem.FeedbackItemType.flow_summary,
                        AiFeedbackItem.FeedbackItemType.interpretation_limit);
        Set<AiFeedbackItem.FeedbackItemType> targetTypes;
        if ("initial".equals(stage)) {
            targetTypes = v2
                    ? Set.of(AiFeedbackItem.FeedbackItemType.initial_market_impact,
                            AiFeedbackItem.FeedbackItemType.initial_prediction_review,
                            AiFeedbackItem.FeedbackItemType.initial_counter_scenario,
                            AiFeedbackItem.FeedbackItemType.interpretation_limit)
                    : Set.of(AiFeedbackItem.FeedbackItemType.initial_uncertainty,
                            AiFeedbackItem.FeedbackItemType.initial_counter_scenario,
                            AiFeedbackItem.FeedbackItemType.initial_factor_to_review,
                            AiFeedbackItem.FeedbackItemType.interpretation_limit);
        } else {
            targetTypes = v2
                    ? Set.of(AiFeedbackItem.FeedbackItemType.final_observed_flow,
                            AiFeedbackItem.FeedbackItemType.final_market_explanation,
                            AiFeedbackItem.FeedbackItemType.final_prediction_review,
                            AiFeedbackItem.FeedbackItemType.final_takeaway,
                            AiFeedbackItem.FeedbackItemType.interpretation_limit)
                    : Set.of(AiFeedbackItem.FeedbackItemType.final_observed_flow,
                            AiFeedbackItem.FeedbackItemType.final_co_observed_factors,
                            AiFeedbackItem.FeedbackItemType.final_reflection_improvement,
                            AiFeedbackItem.FeedbackItemType.final_next_hypothesis,
                            AiFeedbackItem.FeedbackItemType.interpretation_limit);
        }
        Map<String, Set<AiFeedbackItem.FeedbackItemType>> byScope = new LinkedHashMap<>();
        for (Item item : items) {
            Set<AiFeedbackItem.FeedbackItemType> expected =
                    item.sessionTargetId() == null ? sessionTypes : targetTypes;
            if (!expected.contains(item.type())) {
                throw new IllegalStateException("approved_item_invalid_type_or_scope");
            }
            String scope = item.sessionTargetId() == null ? "session" : item.sessionTargetId().toString();
            if (!byScope.computeIfAbsent(scope, ignored -> new HashSet<>()).add(item.type())) {
                throw new IllegalStateException("approved_item_duplicate_type_in_scope");
            }
        }
        if (!byScope.getOrDefault("session", Set.of())
                .contains(AiFeedbackItem.FeedbackItemType.flow_summary)) {
            throw new IllegalStateException("approved_flow_summary_missing");
        }
        for (UUID targetId : targets) {
            if (byScope.getOrDefault(targetId.toString(), Set.of()).isEmpty()) {
                throw new IllegalStateException("approved_target_items_missing");
            }
        }
    }

    private void readItems(List<?> values, UUID target, Set<String> seenIds,
                           List<Item> result) {
        Set<Integer> orders = new HashSet<>();
        List<Item> scoped = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> item = object(values.get(index), "item");
            // An optional AI item ID is validated if supplied, but the approved
            // demo/v1 protocol has no item IDs. DB PKs are generated on insert.
            if (item.containsKey("id")) {
                String id = requiredString(item, "id");
                if (!seenIds.add(id)) {
                    throw new IllegalStateException("approved_item_duplicate_id");
                }
            }
            String type = requiredString(item, "type");
            AiFeedbackItem.FeedbackItemType enumType;
            try {
                enumType = AiFeedbackItem.FeedbackItemType.valueOf(type);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("approved_item_unknown_type", ex);
            }
            String content = requiredString(item, "content");
            // A missing sortOrder uses the original JSON array position within
            // the session/target scope. Do not modify the captured JSON/hash.
            int sourceOrder = index;
            if (item.containsKey("sortOrder")) {
                Object orderValue = item.get("sortOrder");
                if (!(orderValue instanceof Number number) || number.intValue() < 0
                        || number.doubleValue() != number.intValue()) {
                    throw new IllegalStateException("approved_item_sort_order_invalid");
                }
                sourceOrder = number.intValue();
            }
            if (!orders.add(sourceOrder)) {
                throw new IllegalStateException("approved_item_sort_order_invalid");
            }
            List<String> links = new ArrayList<>();
            Set<String> uniqueLinks = new HashSet<>();
            for (Object link : array(item.get("referenceIds"), "referenceIds")) {
                if (!(link instanceof String ref) || ref.isBlank() || !uniqueLinks.add(ref)) {
                    throw new IllegalStateException("approved_item_reference_invalid");
                }
                links.add(ref);
            }
            scoped.add(new Item(target, enumType, content, sourceOrder,
                    List.copyOf(links)));
        }
        scoped.sort(Comparator.comparingInt(Item::sourceSortOrder));
        result.addAll(scoped);
    }

    private void expect(Map<String, Object> map, String name, String expected) {
        if (!expected.equals(map.get(name))) {
            throw new IllegalStateException("approved_result_identity_mismatch:" + name);
        }
    }

    private String alias(Map<String, Object> map, String a, String b) {
        Object first = map.get(a);
        Object second = map.get(b);
        if (first != null && second != null && !first.equals(second)) {
            throw new IllegalStateException("approved_reference_alias_conflict");
        }
        Object value = first != null ? first : second;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("approved_reference_id_missing:" + a);
        }
        return text;
    }

    private String requiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new IllegalStateException("approved_result_field_missing:" + key);
        }
        return str;
    }

    private UUID uuid(String raw, String label) {
        try { return UUID.fromString(raw); }
        catch (RuntimeException e) { throw new IllegalStateException("approved_invalid_uuid:" + label, e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("approved_json_missing");
        }
        Object value = json.readValue(raw, Object.class);
        return object(value, "root");
    }

    private Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("approved_object_required:" + field);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException("approved_non_string_key:" + field);
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private List<?> array(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("approved_array_required:" + field);
        }
        return list;
    }

    private List<Map<String, Object>> objects(Object value, String field) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : array(value, field)) {
            result.add(object(row, field));
        }
        return result;
    }

    private String canonicalJson(Object raw) {
        try { return json.writeValueAsString(sorted(raw)); }
        catch (Exception ex) { throw new IllegalStateException("approved_hash_canonicalization_failed", ex); }
    }

    private Object sorted(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, value) -> ordered.put((String) key, sorted(value)));
            return ordered;
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(this::sorted).toList();
        }
        return raw;
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("approved_result_sha256_unavailable", ex);
        }
    }
}
