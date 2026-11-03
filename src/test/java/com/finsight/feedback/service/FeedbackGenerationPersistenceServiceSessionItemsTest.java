
package com.finsight.feedback.service;

import com.finsight.feedback.client.FeedbackModelClient;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackItemReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.learning.repository.LearningSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackGenerationPersistenceServiceSessionItemsTest {

    @Mock
    LearningSessionRepository sessionRepository;

    @Mock
    AiFeedbackRepository feedbackRepository;

    @Mock
    AiFeedbackInputSnapshotRepository snapshotRepository;

    @Mock
    AiFeedbackItemRepository itemRepository;

    @Mock
    AiFeedbackReferenceRepository referenceRepository;

    @Mock
    AiFeedbackItemReferenceRepository itemReferenceRepository;

    @InjectMocks
    FeedbackGenerationPersistenceService service;

    @Test
    void savesV2SessionAndTargetItemsWithUniqueDatabaseSortOrders() {
        UUID feedbackId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        AiFeedback feedback = mock(AiFeedback.class);

        when(feedback.getStatus())
                .thenReturn(AiFeedback.FeedbackStatus.generating);

        when(feedback.getPromptVersion())
                .thenReturn(FeedbackItemRules.V2_PROMPT_VERSION);

        when(feedbackRepository.findByIdForUpdate(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(itemRepository.save(any(AiFeedbackItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FeedbackGenerationInput input =
                new FeedbackGenerationInput(
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

        List<FeedbackModelClient.Item> items = List.of(
                item(null, "flow_summary", "세션 요약", 1),
                item(null, "news_analysis", "뉴스 해설", 2),
                item(targetId, "initial_market_impact", "시장 영향", 1),
                item(targetId, "initial_prediction_review", "예측 검토", 2),
                item(targetId, "initial_counter_scenario", "반대 시나리오", 3)
        );

        service.complete(
                feedbackId,
                AiFeedback.FeedbackStage.INITIAL,
                input,
                new FeedbackModelClient.Result(
                        "model",
                        "old summary",
                        false,
                        items
                )
        );

        ArgumentCaptor<AiFeedbackItem> captor =
                ArgumentCaptor.forClass(AiFeedbackItem.class);

        verify(itemRepository, times(5))
                .save(captor.capture());

        List<AiFeedbackItem> stored =
                captor.getAllValues();

        assertEquals(
                List.of(1, 2, 3, 4, 5),
                stored.stream()
                        .map(AiFeedbackItem::getSortOrder)
                        .toList()
        );

        assertEquals(
                2,
                stored.stream()
                        .filter(saved ->
                                saved.getSessionTargetId() == null)
                        .count()
        );

        verify(feedback).markReady(
                eq("model"),
                eq("세션 요약"),
                eq(false),
                any()
        );
    }

    private FeedbackModelClient.Item item(
            UUID targetId,
            String type,
            String text,
            int sortOrder
    ) {
        return new FeedbackModelClient.Item(
                targetId == null
                        ? null
                        : targetId.toString(),
                type,
                text,
                List.of(),
                sortOrder,
                null
        );
    }
}
