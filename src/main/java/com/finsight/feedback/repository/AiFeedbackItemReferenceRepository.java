package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedbackItemReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AiFeedbackItemReferenceRepository
        extends JpaRepository<
        AiFeedbackItemReference,
        AiFeedbackItemReference.AiFeedbackItemReferenceId
        > {

    @Query("""
            SELECT reference
            FROM AiFeedbackItemReference reference
            WHERE reference.id.aiFeedbackItemId IN :itemIds
            """)
    List<AiFeedbackItemReference> findAllByItemIds(
            @Param("itemIds") Collection<java.util.UUID> itemIds
    );
}