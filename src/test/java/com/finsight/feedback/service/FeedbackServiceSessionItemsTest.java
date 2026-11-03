
package com.finsight.feedback.service;

import com.finsight.feedback.dto.FeedbackDetailResponse;
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
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceSessionItemsTest {

    @Mock
    AiFeedbackRepository feedbackRepository;

    @Mock
    AiFeedbackItemRepository itemRepository;

    @Mock
    AiFeedbackReferenceRepository referenceRepository;

    @Mock
    AiFeedbackItemReferenceRepository itemReferenceRepository;

    @Mock
    FeedbackSourceDocumentRepository documentRepository;

    @Mock
    LearningSessionRepository sessionRepository;

    @InjectMocks
    FeedbackService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    private void stubFeedback(
            AiFeedback.FeedbackStatus status
    ) {
        AiFeedback feedback = mock(AiFeedback.class);

        when(feedbackRepository.findById(feedbackId))
                .thenReturn(Optional.of(feedback));

        when(feedback.getLearningSessionId())
                .thenReturn(sessionId);

        when(feedback.getStatus())
                .thenReturn(status);

        when(feedback.getFeedbackStage())
                .thenReturn(AiFeedback.FeedbackStage.INITIAL);

        LearningSession session = mock(LearningSession.class);

        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getUserId())
                .thenReturn(userId);
    }

    private AiFeedbackItem createMockItem(
            UUID id,
            UUID scope,
            AiFeedbackItem.FeedbackItemType type,
            String content,
            int order
    ) {
        AiFeedbackItem item = mock(AiFeedbackItem.class);

        when(item.getId()).thenReturn(id);
        when(item.getSessionTargetId()).thenReturn(scope);
        when(item.getItemType()).thenReturn(type);
        when(item.getContent()).thenReturn(content);
        when(item.getSortOrder()).thenReturn(order);

        return item;
    }

    @Test
    void returnsSessionItemsWithSharedReferenceAndFlowSummaryCompatibility() {
        stubFeedback(AiFeedback.FeedbackStatus.ready);

        UUID summaryId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        UUID targetItemId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();

        // Mockito 중첩 stubbing 방지를 위해 객체를 먼저 생성
        AiFeedbackItem summaryItem = createMockItem(
                summaryId,
                null,
                AiFeedbackItem.FeedbackItemType.flow_summary,
                "새 요약",
                1
        );

        AiFeedbackItem analysisItem = createMockItem(
                analysisId,
                null,
                AiFeedbackItem.FeedbackItemType.news_analysis,
                "뉴스 해설",
                2
        );

        AiFeedbackItem targetItem = createMockItem(
                targetItemId,
                targetId,
                AiFeedbackItem.FeedbackItemType.initial_market_impact,
                "시장 영향",
                3
        );

        // 모든 mock 객체 설정을 마친 후 Repository 응답 설정
        when(itemRepository
                .findAllByAiFeedbackIdOrderBySortOrderAsc(feedbackId))
                .thenReturn(List.of(
                        summaryItem,
                        analysisItem,
                        targetItem
                ));

        AiFeedbackReference reference =
                mock(AiFeedbackReference.class);

        when(reference.getId()).thenReturn(referenceId);
        when(reference.getSourceDocumentId()).thenReturn(documentId);
        when(reference.getSourceDocumentChunkId()).thenReturn(chunkId);

        when(referenceRepository
                .findAllByAiFeedbackIdOrderBySortOrderAsc(feedbackId))
                .thenReturn(List.of(reference));

        FeedbackSourceDocument document =
                mock(FeedbackSourceDocument.class);

        when(document.getId()).thenReturn(documentId);

        when(documentRepository.findAllById(any()))
                .thenReturn(List.of(document));

        AiFeedbackItemReference link =
                mock(AiFeedbackItemReference.class);

        when(link.getAiFeedbackItemId()).thenReturn(analysisId);
        when(link.getAiFeedbackReferenceId()).thenReturn(referenceId);

        when(itemReferenceRepository.findAllByItemIds(any()))
                .thenReturn(List.of(link));

        FeedbackDetailResponse detail =
                service.findById(userId, feedbackId);

        // 기존 flowSummary 호환성 검증
        assertEquals("새 요약", detail.flowSummary());

        // 세션 공통 항목 반환 검증
        assertEquals(2, detail.sessionItems().size());

        assertEquals(
                "flow_summary",
                detail.sessionItems().get(0).type()
        );

        assertEquals(
                "news_analysis",
                detail.sessionItems().get(1).type()
        );

        // 세션 뉴스 해설 인용 연결 검증
        assertEquals(
                List.of(referenceId),
                detail.sessionItems().get(1).referenceIds()
        );

        // 대상 항목 분리 검증
        assertEquals(1, detail.targetFeedbacks().size());

        assertEquals(
                targetId,
                detail.targetFeedbacks().getFirst().sessionTargetId()
        );

        assertEquals(
                "initial_market_impact",
                detail.targetFeedbacks()
                        .getFirst()
                        .items()
                        .getFirst()
                        .type()
        );

        // 인용 목록 유지 검증
        assertEquals(1, detail.references().size());
    }

    @Test
    void nonReadyFeedbackNeverExposesItems() {
        stubFeedback(AiFeedback.FeedbackStatus.generating);

        FeedbackDetailResponse detail =
                service.findById(userId, feedbackId);

        assertTrue(detail.sessionItems().isEmpty());
        assertTrue(detail.targetFeedbacks().isEmpty());
        assertTrue(detail.references().isEmpty());
    }
}
