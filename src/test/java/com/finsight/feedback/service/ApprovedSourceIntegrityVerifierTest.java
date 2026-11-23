package com.finsight.feedback.service;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import com.finsight.feedback.repository.FeedbackSourceDocumentChunkRepository;
import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovedSourceIntegrityVerifierTest {
    @Mock FeedbackSourceDocumentRepository documentRepository;
    @Mock FeedbackSourceDocumentChunkRepository chunkRepository;
    @InjectMocks ApprovedSourceIntegrityVerifier verifier;

    private final UUID docId = UUID.randomUUID();
    private final UUID chunkId = UUID.randomUUID();

    private ApprovedCitationWriter.Citation citation() {
        return new ApprovedCitationWriter.Citation("R-A", docId, chunkId,
                "extracted text", "news_analysis");
    }

    private void sources(String storedBody, String originalBody) throws Exception {
        FeedbackSourceDocument doc = mock(FeedbackSourceDocument.class);
        when(doc.getId()).thenReturn(docId);
        when(doc.getContentHash()).thenReturn(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(originalBody.getBytes(StandardCharsets.UTF_8))));
        when(documentRepository.findAllById(anyCollection())).thenReturn(List.of(doc));
        FeedbackSourceDocumentChunk chunk = mock(FeedbackSourceDocumentChunk.class);
        when(chunk.getSourceDocumentId()).thenReturn(docId);
        when(chunk.getChunkOrder()).thenReturn(0);
        when(chunk.getContent()).thenReturn(storedBody);
        when(chunk.getId()).thenReturn(chunkId);
        when(chunkRepository.findAllForDocuments(anyCollection())).thenReturn(List.of(chunk));
    }

    @Test void exactStoredBytesPass() throws Exception {
        sources("한국어   source\\n", "한국어   source\\n");
        assertDoesNotThrow(() -> verifier.verify(List.of(citation())));
    }
    @Test void modifiedStoredBytesBlockApproval() throws Exception {
        sources("changed content", "original content");
        assertThrows(IllegalStateException.class, () -> verifier.verify(List.of(citation())));
    }
    @Test void missingSourceBlocksApproval() {
        when(documentRepository.findAllById(anyCollection())).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> verifier.verify(List.of(citation())));
        verifyNoInteractions(chunkRepository);
    }
}
