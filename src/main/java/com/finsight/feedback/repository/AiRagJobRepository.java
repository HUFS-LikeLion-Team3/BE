package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiRagJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AiRagJobRepository
        extends JpaRepository<AiRagJob, UUID> {

    Optional<AiRagJob> findBySessionIdAndStage(
            UUID sessionId,
            String stage
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT j FROM AiRagJob j " +
                    "WHERE j.feedbackId = :feedbackId"
    )
    Optional<AiRagJob> findByIdForUpdate(
            @Param("feedbackId") UUID feedbackId
    );
}