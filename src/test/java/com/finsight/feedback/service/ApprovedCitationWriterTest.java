package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiFeedbackItemReference;
import com.finsight.feedback.entity.AiFeedbackReference;
import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.feedback.repository.AiFeedbackItemReferenceRepository;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackReferenceRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentChunkRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovedCitationWriterTest {

    @Mock AiFeedbackReferenceRepository referenceRepository;
    @Mock AiFeedbackItemReferenceRepository itemReferenceRepository;
    @Mock AiFeedbackItemRepository itemRepository;
    @Mock FeedbackSourceDocumentRepository documentRepository;
    @Mock FeedbackSourceDocumentChunkRepository chunkRepository;

    @InjectMocks ApprovedCitationWriter writer;

    private final UUID feedbackId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();
    private final UUID sessionItemId = UUID.randomUUID();
    private final UUID targetItemId = UUID.randomUUID();

    private void validSourcesAndItems() {
        FeedbackSourceDocument document = mock(FeedbackSourceDocument.class);
        when(document.getId()).thenReturn(documentId);
        when(documentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(document));

        FeedbackSourceDocumentChunk chunk = mock(FeedbackSourceDocumentChunk.class);
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getSourceDocumentId()).thenReturn(documentId);
        when(chunkRepository.findAllById(anyCollection()))
                .thenReturn(List.of(chunk));

        AiFeedbackItem sessionItem = mock(AiFeedbackItem.class);
        when(sessionItem.getId()).thenReturn(sessionItemId);
        when(sessionItem.getAiFeedbackId()).thenReturn(feedbackId);

        AiFeedbackItem targetItem = mock(AiFeedbackItem.class);
        when(targetItem.getId()).thenReturn(targetItemId);
        when(targetItem.getAiFeedbackId()).thenReturn(feedbackId);
        when(itemRepository.findAllById(anyCollection()))
                .thenReturn(List.of(sessionItem, targetItem));
    }

    private static ApprovedCitationWriter.Citation citation(
            String id, UUID documentId, UUID chunkId,
            String excerpt, String usageContext) {
        return new ApprovedCitationWriter.Citation(
                id, documentId, chunkId, excerpt, usageContext);
    }

    @Test
    void sameChunkDifferentRealCitationsAndSharedLinksAreNotMergedOrTruncated() {
        validSourcesAndItems();
        String longExcerpt = "X".repeat(510) + "TAIL_OK";
        UUID beIdA = UUID.randomUUID();
        UUID beIdB = UUID.randomUUID();

        when(referenceRepository.save(any(AiFeedbackReference.class)))
                .thenAnswer(invocation -> {
                    AiFeedbackReference saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            saved, "id", saved.getSortOrder() == 1 ? beIdA : beIdB);
                    return saved;
                });

        Map<String, UUID> mapping = writer.write(feedbackId, List.of(
                citation("rag-ref-a", documentId, chunkId, longExcerpt, "news_analysis"),
                citation("rag-ref-b", documentId, chunkId, "Second citation", "market_impact")
        ), List.of(
                new ApprovedCitationWriter.ItemLinks(
                        sessionItemId, List.of("rag-ref-a")),
                new ApprovedCitationWriter.ItemLinks(
                        targetItemId, List.of("rag-ref-a", "rag-ref-b"))
        ));

        assertEquals(beIdA, mapping.get("rag-ref-a"));
        assertEquals(beIdB, mapping.get("rag-ref-b"));
        assertNotEquals(beIdA, beIdB);

        ArgumentCaptor<AiFeedbackReference> refs =
                ArgumentCaptor.forClass(AiFeedbackReference.class);
        verify(referenceRepository, times(2)).save(refs.capture());
        assertEquals(chunkId, refs.getAllValues().get(0).getSourceDocumentChunkId());
        assertEquals(chunkId, refs.getAllValues().get(1).getSourceDocumentChunkId());
        assertEquals(longExcerpt, refs.getAllValues().get(0).getCitationExcerpt());
        assertEquals("TAIL_OK", refs.getAllValues().get(0).getCitationExcerpt()
                .substring(510));
        assertEquals("news_analysis", refs.getAllValues().get(0).getUsageContext());
        assertEquals("market_impact", refs.getAllValues().get(1).getUsageContext());
        assertEquals(1, refs.getAllValues().get(0).getSortOrder());
        assertEquals(2, refs.getAllValues().get(1).getSortOrder());

        ArgumentCaptor<AiFeedbackItemReference> links =
                ArgumentCaptor.forClass(AiFeedbackItemReference.class);
        verify(itemReferenceRepository, times(3)).save(links.capture());
        assertEquals(sessionItemId, links.getAllValues().get(0).getAiFeedbackItemId());
        assertEquals(beIdA, links.getAllValues().get(0).getAiFeedbackReferenceId());
        assertEquals(targetItemId, links.getAllValues().get(1).getAiFeedbackItemId());
        assertEquals(beIdA, links.getAllValues().get(1).getAiFeedbackReferenceId());
        assertEquals(beIdB, links.getAllValues().get(2).getAiFeedbackReferenceId());
    }

    @Test
    void missingSourceChunkRejectsBeforeFirstWrite() {
        FeedbackSourceDocument document = mock(FeedbackSourceDocument.class);
        when(document.getId()).thenReturn(documentId);
        when(documentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(document));
        when(chunkRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> writer.write(
                feedbackId,
                List.of(citation("rag-1", documentId, chunkId, "excerpt", "reason")),
                List.of()
        ));
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }

    @Test
    void chunkBelongingToDifferentDocumentRejectsBeforeWriting() {
        FeedbackSourceDocument document = mock(FeedbackSourceDocument.class);
        when(document.getId()).thenReturn(documentId);
        when(documentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(document));
        FeedbackSourceDocumentChunk chunk = mock(FeedbackSourceDocumentChunk.class);
        when(chunk.getId()).thenReturn(chunkId);
        when(chunk.getSourceDocumentId()).thenReturn(UUID.randomUUID());
        when(chunkRepository.findAllById(anyCollection()))
                .thenReturn(List.of(chunk));

        assertThrows(IllegalStateException.class, () -> writer.write(
                feedbackId,
                List.of(citation("rag-1", documentId, chunkId, "excerpt", "reason")),
                List.of()
        ));
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }

    @Test
    void unknownReferenceIdRejectsWithoutPartialWrite() {
        assertThrows(IllegalStateException.class, () -> writer.write(
                feedbackId,
                List.of(citation("rag-1", documentId, chunkId, "excerpt", "reason")),
                List.of(new ApprovedCitationWriter.ItemLinks(
                        sessionItemId, List.of("rag-not-in-approved-result")))
        ));
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }

    @Test
    void duplicateRagReferenceIdIsRejectedEvenWhenChunkMatches() {
        assertThrows(IllegalStateException.class, () -> writer.write(
                feedbackId,
                List.of(
                        citation("rag-1", documentId, chunkId, "First", "first"),
                        citation("rag-1", documentId, chunkId, "Second", "second")
                ), List.of()
        ));
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }

    @Test
    void otherFeedbackItemCannotLinkApprovedReference() {
        AiFeedbackItem foreignItem = mock(AiFeedbackItem.class);
        when(foreignItem.getId()).thenReturn(sessionItemId);
        when(foreignItem.getAiFeedbackId()).thenReturn(UUID.randomUUID());
        when(itemRepository.findAllById(anyCollection()))
                .thenReturn(List.of(foreignItem));

        assertThrows(IllegalStateException.class, () -> writer.write(
                feedbackId,
                List.of(citation("rag-1", documentId, chunkId, "excerpt", "reason")),
                List.of(new ApprovedCitationWriter.ItemLinks(
                        sessionItemId, List.of("rag-1")))
        ));
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }

    @Test
    void noCitationForInsufficientEvidenceIsAllowedWithoutFabrication() {
        assertTrue(writer.write(feedbackId, List.of(), List.of()).isEmpty());
        verifyNoInteractions(referenceRepository, itemReferenceRepository);
    }
}
