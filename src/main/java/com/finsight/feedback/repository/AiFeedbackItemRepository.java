package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedbackItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiFeedbackItemRepository
        extends JpaRepository<AiFeedbackItem, UUID> {

    List<AiFeedbackItem> findAllByAiFeedbackIdOrderBySortOrderAsc(
            UUID aiFeedbackId
    );
}