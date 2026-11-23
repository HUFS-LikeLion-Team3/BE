package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiFeedback;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiFeedbackRepository
        extends JpaRepository<AiFeedback, UUID> {

    List<AiFeedback> findAllByLearningSessionId(
            UUID learningSessionId
    );

    Optional<AiFeedback> findByRequestId(
            String requestId
    );

    Optional<AiFeedback>
    findByLearningSessionIdAndFeedbackStageAndVersion(
            UUID learningSessionId,
            AiFeedback.FeedbackStage feedbackStage,
            Integer version
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT f
            FROM AiFeedback f
            WHERE f.id = :id
            """)
    Optional<AiFeedback> findByIdForUpdate(
            @Param("id") UUID id
    );
}