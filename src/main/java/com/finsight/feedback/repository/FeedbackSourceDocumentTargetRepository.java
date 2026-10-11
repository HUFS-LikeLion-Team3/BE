package com.finsight.feedback.repository;

import com.finsight.feedback.entity.FeedbackSourceDocumentTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeedbackSourceDocumentTargetRepository
        extends JpaRepository<
        FeedbackSourceDocumentTarget,
        FeedbackSourceDocumentTarget.Key
        > {

    @Query("""
            SELECT t.sourceDocumentId
            FROM FeedbackSourceDocumentTarget t
            WHERE t.marketTargetId IN :marketTargetIds
            """)
    List<UUID> findDocumentIdsByMarketTargetIds(
            @Param("marketTargetIds")
            Collection<UUID> marketTargetIds
    );
}