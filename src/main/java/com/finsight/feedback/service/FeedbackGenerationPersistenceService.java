package com.finsight.feedback.service;

import com.finsight.feedback.client.FeedbackModelClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiFeedbackItemReference;
import com.finsight.feedback.entity.AiFeedbackReference;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackItemReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeedbackGenerationPersistenceService {

    private final LearningSessionRepository sessionRepository;
    private final AiFeedbackRepository feedbackRepository;
    private final AiFeedbackInputSnapshotRepository snapshotRepository;
    private final AiFeedbackItemRepository itemRepository;
    private final AiFeedbackReferenceRepository referenceRepository;
    private final AiFeedbackItemReferenceRepository itemReferenceRepository;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public UUID prepare(
            UUID sessionId,
            AiFeedback.FeedbackStage stage,
            int version,
            String requestId,
            String promptVersion,
            String policyVersion,
            OffsetDateTime cutoff
    ) {
        sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Learning session not found: "
                                        + sessionId
                        )
                );

        var existing =
                feedbackRepository
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                stage,
                                version
                        );

        if (existing.isPresent()) {
            return existing.get().getId();
        }

        AiFeedback feedback =
                feedbackRepository.save(
                        AiFeedback.create(
                                sessionId,
                                stage,
                                version,
                                requestId,
                                promptVersion,
                                policyVersion,
                                cutoff
                        )
                );

        return feedback.getId();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void saveSnapshot(
            UUID feedbackId,
            FeedbackGenerationInput input
    ) {
        var previous =
                snapshotRepository
                        .findByAiFeedbackId(feedbackId);

        if (previous.isPresent()) {
            if (!previous.get()
                    .getInputHash()
                    .equals(input.inputHash())) {
                throw new IllegalStateException(
                        "AI feedback input snapshot mismatch."
                );
            }

            return;
        }

        snapshotRepository.save(
                AiFeedbackInputSnapshot.create(
                        feedbackId,
                        input.requestSnapshot(),
                        input.predictionSnapshot(),
                        input.marketBaselineSnapshot(),
                        input.marketResultSnapshot(),
                        input.reflectionSnapshot(),
                        input.inputHash()
                )
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public boolean claim(UUID feedbackId) {
        AiFeedback feedback =
                feedbackRepository
                        .findByIdForUpdate(feedbackId)
                        .orElseThrow();

        if (feedback.getStatus()
                != AiFeedback.FeedbackStatus.pending) {
            return false;
        }

        feedback.markGenerating();
        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void complete(
            UUID feedbackId,
            AiFeedback.FeedbackStage stage,
            FeedbackGenerationInput input,
            FeedbackModelClient.Result result
    ) {
        AiFeedback feedback =
                feedbackRepository
                        .findByIdForUpdate(feedbackId)
                        .orElseThrow();

        if (feedback.getStatus()
                == AiFeedback.FeedbackStatus.ready) {
            return;
        }

        if (feedback.getStatus()
                != AiFeedback.FeedbackStatus.generating) {
            throw new IllegalStateException(
                    "Feedback is not generating."
            );
        }

        if (result.flowSummary() == null
                || result.flowSummary().isBlank()) {
            throw new IllegalStateException(
                    "AI flow summary is empty."
            );
        }

        List<FeedbackModelClient.Item> sortedItems =
                result.items() == null
                        ? List.of()
                        : result.items()
                          .stream()
                          .sorted(
                                  Comparator.comparingInt(
                                          FeedbackModelClient.Item
                                          ::sortOrder
                                  )
                          )
                          .toList();

        validateItems(
                stage,
                input,
                sortedItems
        );

        Map<UUID, AiFeedbackReference>
                referencesByChunk =
                createReferences(
                        feedbackId,
                        input,
                        sortedItems
                );

        for (FeedbackModelClient.Item resultItem :
                sortedItems) {

            UUID targetId =
                    resultItem.sessionTargetId() == null
                            ? null
                            : UUID.fromString(
                            resultItem.sessionTargetId()
                    );

            AiFeedbackItem item =
                    itemRepository.save(
                            AiFeedbackItem.create(
                                    feedbackId,
                                    targetId,
                                    AiFeedbackItem.FeedbackItemType
                                            .valueOf(
                                                    resultItem.type()
                                            ),
                                    resultItem.content(),
                                    resultItem.variablesToCheck(),
                                    resultItem.sortOrder()
                            )
                    );

            if (resultItem.referenceChunkIds() == null) {
                continue;
            }

            Set<UUID> linked = new HashSet<>();

            for (String rawChunkId :
                    resultItem.referenceChunkIds()) {

                UUID chunkId =
                        UUID.fromString(rawChunkId);

                if (!linked.add(chunkId)) {
                    continue;
                }

                AiFeedbackReference reference =
                        referencesByChunk.get(chunkId);

                if (reference == null) {
                    throw new IllegalStateException(
                            "AI referenced an unavailable chunk: "
                                    + chunkId
                    );
                }

                itemReferenceRepository.save(
                        AiFeedbackItemReference.create(
                                item.getId(),
                                reference.getId()
                        )
                );
            }
        }

        feedback.markReady(
                result.modelVersion(),
                result.flowSummary(),
                result.insufficientEvidence(),
                OffsetDateTime.now()
        );
    }

    private void validateItems(
            AiFeedback.FeedbackStage stage,
            FeedbackGenerationInput input,
            List<FeedbackModelClient.Item> items
    ) {
        Set<Integer> sortOrders = new HashSet<>();

        Set<String> allowedTypes =
                stage == AiFeedback.FeedbackStage.INITIAL
                        ? Set.of(
                        "initial_uncertainty",
                        "initial_counter_scenario",
                        "initial_factor_to_review",
                        "interpretation_limit"
                )
                        : Set.of(
                        "final_observed_flow",
                        "final_co_observed_factors",
                        "final_reflection_improvement",
                        "final_next_hypothesis",
                        "interpretation_limit"
                );

        for (FeedbackModelClient.Item item : items) {
            if (item.content() == null
                    || item.content().isBlank()) {
                throw new IllegalStateException(
                        "AI feedback item body is empty."
                );
            }

            if (!allowedTypes.contains(item.type())) {
                throw new IllegalStateException(
                        "Invalid feedback item type: "
                                + item.type()
                );
            }

            if (!sortOrders.add(item.sortOrder())) {
                throw new IllegalStateException(
                        "Duplicate feedback sort order."
                );
            }

            if (item.sessionTargetId() != null) {
                UUID targetId =
                        UUID.fromString(
                                item.sessionTargetId()
                        );

                if (!input.allowedTargetIds()
                        .contains(targetId)) {
                    throw new IllegalStateException(
                            "AI returned an unknown session target."
                    );
                }
            }

            if (item.referenceChunkIds() != null) {
                for (String rawChunkId :
                        item.referenceChunkIds()) {

                    UUID chunkId =
                            UUID.fromString(rawChunkId);

                    if (!input.evidenceChunks()
                            .containsKey(chunkId)) {
                        throw new IllegalStateException(
                                "AI returned an unknown reference chunk."
                        );
                    }
                }
            }
        }
    }

    private Map<UUID, AiFeedbackReference>
    createReferences(
            UUID feedbackId,
            FeedbackGenerationInput input,
            List<FeedbackModelClient.Item> items
    ) {
        Map<UUID, String> usageByChunk =
                new LinkedHashMap<>();

        for (FeedbackModelClient.Item item : items) {
            if (item.referenceChunkIds() == null) {
                continue;
            }

            for (String rawChunkId :
                    item.referenceChunkIds()) {
                UUID chunkId =
                        UUID.fromString(rawChunkId);

                usageByChunk.putIfAbsent(
                        chunkId,
                        item.type()
                );
            }
        }

        Map<UUID, AiFeedbackReference> saved =
                new HashMap<>();

        int sortOrder = 1;

        for (Map.Entry<UUID, String> entry :
                usageByChunk.entrySet()) {

            FeedbackGenerationInput.EvidenceChunk evidence =
                    input.evidenceChunks()
                            .get(entry.getKey());

            if (evidence == null) {
                throw new IllegalStateException(
                        "Evidence chunk not found."
                );
            }

            AiFeedbackReference reference =
                    referenceRepository.save(
                            AiFeedbackReference.create(
                                    feedbackId,
                                    evidence.sourceDocumentId(),
                                    evidence.chunkId(),
                                    "근거 " + sortOrder,
                                    excerpt(
                                            evidence.content()
                                    ),
                                    entry.getValue(),
                                    sortOrder
                            )
                    );

            saved.put(
                    evidence.chunkId(),
                    reference
            );

            sortOrder++;
        }

        return saved;
    }

    private String excerpt(String content) {
        if (content == null) {
            return null;
        }

        int maxLength = 500;

        if (content.length() <= maxLength) {
            return content;
        }

        return content.substring(0, maxLength);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW
    )
    public void fail(
            UUID feedbackId,
            String failureCode
    ) {
        feedbackRepository
                .findByIdForUpdate(feedbackId)
                .ifPresent(feedback ->
                        feedback.markFailed(
                                failureCode
                        )
                );
    }
}