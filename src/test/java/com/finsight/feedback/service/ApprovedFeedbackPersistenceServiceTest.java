package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiFeedbackItem;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackItemRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovedFeedbackPersistenceServiceTest {
    @Mock AiFeedbackRepository feedbackRepository;
    @Mock AiRagJobRepository jobRepository;
    @Mock AiFeedbackItemRepository itemRepository;
    @Mock ApprovedCitationWriter citationWriter;
    @Mock ApprovedRagResultParser parser;
    @Mock ApprovedSourceIntegrityVerifier sourceIntegrity;
    @InjectMocks ApprovedFeedbackPersistenceService service;

    private final UUID feedbackId = UUID.randomUUID();

    @Test
    void approvedItemsAndCitationsArePersistedBeforeReady() {
        AiFeedback feedback = mock(AiFeedback.class);
        AiRagJob job = mock(AiRagJob.class);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.generating);
        when(feedbackRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(feedback));
        when(jobRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(job));
        UUID target = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        OffsetDateTime generated = OffsetDateTime.parse("2026-11-03T10:00:00+09:00");
        when(parser.parse(job, feedback)).thenReturn(new ApprovedRagResultParser.Parsed(
                "local-demo", "actual-prompt", "actual-policy", generated,
                "approved flow summary", false,
                List.of(
                        new ApprovedRagResultParser.Item(null,
                                AiFeedbackItem.FeedbackItemType.flow_summary,
                                "approved flow summary", 0, List.of()),
                        new ApprovedRagResultParser.Item(target,
                                AiFeedbackItem.FeedbackItemType.initial_market_impact,
                                "content", 0, List.of("R1", "R2"))),
                List.of(new ApprovedCitationWriter.Citation("R1", doc, chunk, "a", "scope1"),
                        new ApprovedCitationWriter.Citation("R2", doc, chunk, "b", "scope2"))));
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        AiFeedbackItem first = mock(AiFeedbackItem.class);
        AiFeedbackItem second = mock(AiFeedbackItem.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(itemRepository.save(any(AiFeedbackItem.class))).thenReturn(first, second);

        assertTrue(service.persistCaptured(feedbackId));
        ArgumentCaptor<List<ApprovedCitationWriter.ItemLinks>> links = ArgumentCaptor.forClass(List.class);
        verify(sourceIntegrity).verify(anyList());
        verify(citationWriter).write(eq(feedbackId), anyList(), links.capture());
        assertEquals(firstId, links.getValue().get(0).itemId());
        assertEquals(List.of("R1", "R2"), links.getValue().get(1).referenceIds());
        verify(feedback).markApprovedReady("local-demo", "actual-prompt", "actual-policy",
                "approved flow summary", false, generated);
    }

    @Test
    void repeatedReadyFeedbackDoesNotInsertDuplicateRows() {
        AiFeedback feedback = mock(AiFeedback.class);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.ready);
        when(feedbackRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(feedback));
        when(jobRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(mock(AiRagJob.class)));

        assertFalse(service.persistCaptured(feedbackId));
        verifyNoInteractions(parser, sourceIntegrity, itemRepository, citationWriter);
    }

    @Test
    void invalidApprovedResponseDoesNotWriteAnyRowsOrMarkReady() {
        AiFeedback feedback = mock(AiFeedback.class);
        AiRagJob job = mock(AiRagJob.class);
        when(feedback.getStatus()).thenReturn(AiFeedback.FeedbackStatus.generating);
        when(feedbackRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(feedback));
        when(jobRepository.findByIdForUpdate(feedbackId)).thenReturn(Optional.of(job));
        when(parser.parse(job, feedback)).thenThrow(new IllegalStateException("approved_result_hash_mismatch"));

        assertThrows(IllegalStateException.class, () -> service.persistCaptured(feedbackId));
        verifyNoInteractions(sourceIntegrity, itemRepository, citationWriter);
        verify(feedback, never()).markApprovedReady(anyString(), anyString(), anyString(),
                anyString(), anyBoolean(), any());
    }
}
