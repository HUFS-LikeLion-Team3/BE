package com.finsight.feedback.repository;

import com.finsight.feedback.entity.FeedbackSourceDocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeedbackSourceDocumentChunkRepository
        extends JpaRepository<FeedbackSourceDocumentChunk, UUID> {

    @Query("""
            SELECT c
            FROM FeedbackSourceDocumentChunk c
            WHERE c.sourceDocumentId IN :documentIds
            ORDER BY c.sourceDocumentId, c.chunkOrder
            """)
    List<FeedbackSourceDocumentChunk> findAllForDocuments(
            @Param("documentIds")
            Collection<UUID> documentIds
    );
}