package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedbackInputSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiFeedbackInputSnapshotRepository
        extends JpaRepository<AiFeedbackInputSnapshot, UUID> {

    Optional<AiFeedbackInputSnapshot> findByAiFeedbackId(
            UUID aiFeedbackId
    );
}