package com.finsight.prediction.repository;

import com.finsight.prediction.entity.SessionTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionTargetRepository
        extends JpaRepository<SessionTarget, UUID> {

    List<SessionTarget> findAllByLearningSessionId(
            UUID learningSessionId
    );
}
