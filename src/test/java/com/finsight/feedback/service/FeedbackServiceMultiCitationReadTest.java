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

/**
 * Issue #26 public read-path regression: distinct citations from the same chunk,
 * exact excerpts beyond 500 characters and shared item-reference links.
 * This verifies the public DTO path, not RAG ingestion or a MySQL round trip.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceMultiCitationReadTest {

    @Mock private AiFeedbackRepository feedbackRepository;
    @Mock private AiFeedbackItemRepository itemRepository;
    @Mock private AiFeedbackReferenceRepository referenceRepository;
    @Mock private AiFeedbackItemReferenceRepository itemReferenceRepository;
    @Mock private FeedbackSourceDocumentRepository documentRepository;
    @Mock private LearningSessionRepository sessionRepository;

    @InjectMocks private FeedbackService service;

    @Test
    void returnsDistinctSameChunkCitationsAndSharedLinksWithoutTruncation() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID feedbackId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        UUID summaryItemId = UUID.randomUUID();
        UUID analysisItemId = UUID.randomUUID();
        UUID targetItemId = UUID.randomUUID();
        UUID firstReferenceId = UUID.randomUUID();
        UUID secondReferenceId = UUID.randomUUID();

        AiFeedback feedback = mock(AiFeedback.class);
        when(feedbackRepository.findById(feedbackId)).thenReturn(Optional.of(feedback));
        when(feedback.getLearningSessionId()).thenReturn(sessionId);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.ready);
        when(feedback.getFeedbackStage()).thenReturn(AiFeedback.FeedbackStage.INITIAL);

        LearningSession session = mock(LearningSession.class);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(session.getUserId()).thenReturn(userId);

        AiFeedbackItem summary = item(summaryItemId, null,
                AiFeedbackItem.FeedbackItemType.flow_summary, "판단 흐름 요약", 1);
        AiFeedbackItem analysis = item(analysisItemId, null,
                AiFeedbackItem.FeedbackItemType.news_analysis, "세션 뉴스 해설", 2);
        AiFeedbackItem target = item(targetItemId, targetId,
                AiFeedbackItem.FeedbackItemType.initial_market_impact, "대상별 설명", 3);
        when(itemRepository.findAllByAiFeedbackIdOrderBySortOrderAsc(feedbackId))
                .thenReturn(List.of(summary, analysis, target));

        // The actual citation begins beyond the former first-500-character cutoff.
        String longExcerpt = "가".repeat(520) + "이 문장은 500자 이후의 실제 인용이다.";
        String secondExcerpt = "같은 조각의 다른 인용 문장";

        AiFeedbackReference first = reference(firstReferenceId, documentId, chunkId,
                longExcerpt, "뉴스 흐름 해설");
        AiFeedbackReference second = reference(secondReferenceId, documentId, chunkId,
                secondExcerpt, "시장 영향 설명");
        when(referenceRepository.findAllByAiFeedbackIdOrderBySortOrderAsc(feedbackId))
                .thenReturn(List.of(first, second));

        FeedbackSourceDocument source = mock(FeedbackSourceDocument.class);
        when(source.getId()).thenReturn(documentId);
        when(documentRepository.findAllById(any())).thenReturn(List.of(source));

        when(itemReferenceRepository.findAllByItemIds(any())).thenReturn(List.of(
                AiFeedbackItemReference.create(analysisItemId, firstReferenceId),
                AiFeedbackItemReference.create(targetItemId, firstReferenceId),
                AiFeedbackItemReference.create(targetItemId, secondReferenceId)
        ));

        FeedbackDetailResponse result = service.findById(userId, feedbackId);

        assertEquals("판단 흐름 요약", result.flowSummary());
        assertEquals(2, result.sessionItems().size());
        assertEquals(List.of(firstReferenceId), result.sessionItems().get(1).referenceIds());
        assertEquals(targetId, result.targetFeedbacks().getFirst().sessionTargetId());
        assertEquals(List.of(firstReferenceId, secondReferenceId),
                result.targetFeedbacks().getFirst().items().getFirst().referenceIds());

        assertEquals(2, result.references().size());
        assertEquals(firstReferenceId, result.references().get(0).id());
        assertEquals(secondReferenceId, result.references().get(1).id());
        assertEquals(chunkId, result.references().get(0).chunkId());
        assertEquals(chunkId, result.references().get(1).chunkId());
        assertEquals(documentId, result.references().get(0).sourceDocumentId());
        assertEquals(documentId, result.references().get(1).sourceDocumentId());
        assertEquals(longExcerpt, result.references().get(0).citationExcerpt());
        assertTrue(result.references().get(0).citationExcerpt().length() > 500);
        assertEquals(secondExcerpt, result.references().get(1).citationExcerpt());
        assertEquals("뉴스 흐름 해설", result.references().get(0).usageContext());
        assertEquals("시장 영향 설명", result.references().get(1).usageContext());
    }

    private AiFeedbackItem item(UUID id, UUID targetId,
                                AiFeedbackItem.FeedbackItemType type,
                                String content, int sortOrder) {
        AiFeedbackItem item = mock(AiFeedbackItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getSessionTargetId()).thenReturn(targetId);
        when(item.getItemType()).thenReturn(type);
        when(item.getContent()).thenReturn(content);
        when(item.getSortOrder()).thenReturn(sortOrder);
        return item;
    }

    private AiFeedbackReference reference(UUID id, UUID documentId, UUID chunkId,
                                          String excerpt, String usage) {
        AiFeedbackReference reference = mock(AiFeedbackReference.class);
        when(reference.getId()).thenReturn(id);
        when(reference.getSourceDocumentId()).thenReturn(documentId);
        when(reference.getSourceDocumentChunkId()).thenReturn(chunkId);
        when(reference.getCitationExcerpt()).thenReturn(excerpt);
        when(reference.getUsageContext()).thenReturn(usage);
        return reference;
    }
}
