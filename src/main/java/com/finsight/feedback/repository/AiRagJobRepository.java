package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.entity.AiFeedback;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiRagJobRepository
        extends JpaRepository<AiRagJob, UUID> {

    Optional<AiRagJob> findBySessionIdAndStage(
            UUID sessionId,
            String stage
    );

    /** Rescan persisted work after process restart; never automatically retry AI generation. */
    @Query("""
            SELECT j.feedbackId FROM AiRagJob j, AiFeedback f
            WHERE j.feedbackId = f.id
              AND j.jobId IS NOT NULL
              AND j.state NOT IN ('stored', 'reconciliation_required')
              AND f.status IN :statuses
            ORDER BY j.createdAt, j.feedbackId
            """)
    List<UUID> findDeliveryCandidates(
            @Param("statuses") List<AiFeedback.FeedbackStatus> statuses
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