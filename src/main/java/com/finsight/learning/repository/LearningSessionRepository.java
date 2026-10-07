package com.finsight.learning.repository;

import com.finsight.learning.entity.LearningSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LearningSessionRepository extends JpaRepository<LearningSession, UUID> {

    Optional<LearningSession> findByUserIdAndNewsId(
            UUID userId,
            UUID newsId
    );

    @Query("""
            SELECT l
            FROM LearningSession l
            WHERE l.userId = :userId
              AND (:newsId IS NULL OR l.newsId = :newsId)
              AND (:status IS NULL OR l.status = :status)
              AND (:sessionType IS NULL OR l.sessionType = :sessionType)
            """)
    Page<LearningSession> findByCondition(
            @Param("userId") UUID userId,
            @Param("newsId") UUID newsId,
            @Param("status") LearningSession.LearningStatus status,
            @Param("sessionType") LearningSession.SessionType sessionType,
            Pageable pageable
    );
}
