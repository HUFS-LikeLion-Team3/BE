package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedbackReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiFeedbackReferenceRepository
        extends JpaRepository<AiFeedbackReference, UUID> {

    List<AiFeedbackReference> findAllByAiFeedbackIdOrderBySortOrderAsc(
            UUID aiFeedbackId
    );
}