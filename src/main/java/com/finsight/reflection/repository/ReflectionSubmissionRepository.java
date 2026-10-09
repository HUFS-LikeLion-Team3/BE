package com.finsight.reflection.repository;

import com.finsight.reflection.entity.ReflectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReflectionSubmissionRepository extends JpaRepository<ReflectionSubmission, UUID> {
    Optional<ReflectionSubmission> findByLearningSessionIdAndClientRequestId(UUID sessionId, UUID clientRequestId);
}
