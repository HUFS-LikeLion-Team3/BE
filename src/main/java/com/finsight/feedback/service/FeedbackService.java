package com.finsight.feedback.service;

import com.finsight.feedback.dto.FeedbackDetailResponse;
import com.finsight.feedback.dto.FeedbackListItemResponse;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiFeedbackItemReference;
import com.finsight.feedback.entity.AiFeedbackReference;
import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.repository.AiFeedbackItemReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackService {

    private final AiFeedbackRepository aiFeedbackRepository;
    private final AiFeedbackItemRepository aiFeedbackItemRepository;
    private final AiFeedbackReferenceRepository aiFeedbackReferenceRepository;
    private final AiFeedbackItemReferenceRepository aiFeedbackItemReferenceRepository;
    private final FeedbackSourceDocumentRepository feedbackSourceDocumentRepository;
    private final LearningSessionRepository learningSessionRepository;

    public List<FeedbackListItemResponse> findAll(
            UUID userId,
            UUID learningSessionId
    ) {
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "요청한 세션 또는 기록을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 기록에 접근할 권한이 없습니다."
            );
        }

        return aiFeedbackRepository.findAllByLearningSessionId(learningSessionId)
                .stream()
                .sorted(
                        Comparator
                                .comparingInt((AiFeedback feedback) ->
                                        feedback.getFeedbackStage().ordinal())
                                .thenComparing(AiFeedback::getVersion)
                )
                .map(FeedbackListItemResponse::from)
                .toList();
    }

    public FeedbackDetailResponse findById(
            UUID userId,
            UUID feedbackId
    ) {
        AiFeedback feedback =
                aiFeedbackRepository.findById(feedbackId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "AI 피드백을 찾을 수 없습니다."
                        ));

        LearningSession session =
                learningSessionRepository.findById(
                                feedback.getLearningSessionId()
                        )
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "AI 피드백을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 기록에 접근할 권한이 없습니다."
            );
        }

        if (feedback.getStatus() != AiFeedback.FeedbackStatus.ready) {
            return FeedbackDetailResponse.from(
                    feedback,
                    null,
                    List.of(),
                    List.of()
            );
        }

        List<AiFeedbackItem> items =
                aiFeedbackItemRepository
                        .findAllByAiFeedbackIdOrderBySortOrderAsc(
                                feedbackId
                        );

        List<AiFeedbackReference> references =
                aiFeedbackReferenceRepository
                        .findAllByAiFeedbackIdOrderBySortOrderAsc(
                                feedbackId
                        );

        Map<UUID, AiFeedbackReference> referencesById =
                references.stream()
                        .collect(Collectors.toMap(
                                AiFeedbackReference::getId,
                                Function.identity()
                        ));

        Set<UUID> sourceDocumentIds =
                references.stream()
                        .map(AiFeedbackReference::getSourceDocumentId)
                        .collect(Collectors.toSet());

        Map<UUID, FeedbackSourceDocument> sourceDocumentsById =
                feedbackSourceDocumentRepository
                        .findAllById(sourceDocumentIds)
                        .stream()
                        .collect(Collectors.toMap(
                                FeedbackSourceDocument::getId,
                                Function.identity()
                        ));

        List<FeedbackDetailResponse.ReferenceResponse> referenceResponses =
                references.stream()
                        .map(reference -> {
                            FeedbackSourceDocument document =
                                    sourceDocumentsById.get(
                                            reference.getSourceDocumentId()
                                    );

                            if (document == null) {
                                throw new ApiException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "서버 내부 오류가 발생했습니다."
                                );
                            }

                            return new FeedbackDetailResponse.ReferenceResponse(
                                    reference.getId(),
                                    reference.getSourceDocumentId(),
                                    reference.getSourceDocumentChunkId(),
                                    document.getTitle(),
                                    document.getPublisher(),
                                    document.getPublishedAt(),
                                    document.getUrl(),
                                    document.getContentHash(),
                                    reference.getCitationLabel(),
                                    reference.getCitationExcerpt(),
                                    reference.getUsageContext()
                            );
                        })
                        .toList();

        Map<UUID, Integer> referenceOrder = new HashMap<>();

        for (int i = 0; i < references.size(); i++) {
            referenceOrder.put(
                    references.get(i).getId(),
                    i
            );
        }

        Map<UUID, List<UUID>> referenceIdsByItemId =
                createReferenceIdsByItemId(
                        items,
                        referencesById,
                        referenceOrder
                );

        Map<UUID, List<FeedbackDetailResponse.FeedbackItemResponse>>
                itemsBySessionTargetId = new LinkedHashMap<>();

        for (AiFeedbackItem item : items) {
            if (item.getSessionTargetId() == null) {
                continue;
            }

            FeedbackDetailResponse.FeedbackItemResponse itemResponse =
                    new FeedbackDetailResponse.FeedbackItemResponse(
                            item.getId(),
                            item.getItemType().name(),
                            item.getContent(),
                            referenceIdsByItemId.getOrDefault(
                                    item.getId(),
                                    List.of()
                            ),
                            item.getSortOrder()
                    );

            itemsBySessionTargetId
                    .computeIfAbsent(
                            item.getSessionTargetId(),
                            key -> new ArrayList<>()
                    )
                    .add(itemResponse);
        }

        List<FeedbackDetailResponse.TargetFeedbackResponse> targetFeedbacks =
                itemsBySessionTargetId.entrySet()
                        .stream()
                        .map(entry ->
                                new FeedbackDetailResponse.TargetFeedbackResponse(
                                        entry.getKey(),
                                        entry.getValue()
                                )
                        )
                        .toList();

        return FeedbackDetailResponse.from(
                feedback,
                feedback.getFlowSummary(),
                targetFeedbacks,
                referenceResponses
        );
    }

    private Map<UUID, List<UUID>> createReferenceIdsByItemId(
            List<AiFeedbackItem> items,
            Map<UUID, AiFeedbackReference> referencesById,
            Map<UUID, Integer> referenceOrder
    ) {
        if (items.isEmpty()) {
            return Map.of();
        }

        List<UUID> itemIds =
                items.stream()
                        .map(AiFeedbackItem::getId)
                        .toList();

        List<AiFeedbackItemReference> itemReferences =
                aiFeedbackItemReferenceRepository
                        .findAllByItemIds(itemIds);

        Map<UUID, List<UUID>> result = new HashMap<>();

        for (AiFeedbackItemReference itemReference : itemReferences) {
            UUID referenceId =
                    itemReference.getAiFeedbackReferenceId();

            if (!referencesById.containsKey(referenceId)) {
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "서버 내부 오류가 발생했습니다."
                );
            }

            result.computeIfAbsent(
                            itemReference.getAiFeedbackItemId(),
                            key -> new ArrayList<>()
                    )
                    .add(referenceId);
        }

        for (List<UUID> referenceIds : result.values()) {
            referenceIds.sort(
                    Comparator.comparingInt(
                            id -> referenceOrder.getOrDefault(
                                    id,
                                    Integer.MAX_VALUE
                            )
                    )
            );
        }

        return result;
    }
}