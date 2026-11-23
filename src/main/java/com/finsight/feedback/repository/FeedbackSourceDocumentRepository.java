package com.finsight.feedback.repository;

import com.finsight.feedback.entity.FeedbackSourceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeedbackSourceDocumentRepository
        extends JpaRepository<FeedbackSourceDocument, UUID> {

    @Query("""
            SELECT d
            FROM FeedbackSourceDocument d
            WHERE d.news.id = :newsId
            ORDER BY d.id ASC
            """)
    List<FeedbackSourceDocument> findByNewsIdOrderByIdAsc(
            @Param("newsId") UUID newsId
    );

    @Query("""
            SELECT d
            FROM FeedbackSourceDocument d
            WHERE d.news.id = :newsId
              AND d.publishedAt <= :cutoff
            ORDER BY d.publishedAt ASC, d.id ASC
            """)
    List<FeedbackSourceDocument> findEligibleByNewsId(
            @Param("newsId") UUID newsId,
            @Param("cutoff") OffsetDateTime cutoff
    );

    @Query("""
            SELECT d
            FROM FeedbackSourceDocument d
            WHERE d.id IN :documentIds
              AND d.publishedAt <= :cutoff
            ORDER BY d.publishedAt ASC, d.id ASC
            """)
    List<FeedbackSourceDocument> findEligibleByIds(
            @Param("documentIds") Collection<UUID> documentIds,
            @Param("cutoff") OffsetDateTime cutoff
    );
}