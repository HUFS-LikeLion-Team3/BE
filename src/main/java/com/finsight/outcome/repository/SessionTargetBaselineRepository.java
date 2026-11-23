package com.finsight.outcome.repository;

import com.finsight.outcome.entity.SessionTargetBaseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionTargetBaselineRepository
        extends JpaRepository<SessionTargetBaseline, UUID> {

    Optional<SessionTargetBaseline> findBySessionTargetId(
            UUID sessionTargetId
    );
}