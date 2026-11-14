package com.finsight.pattern.repository;

import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.prediction.entity.Prediction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** P1 개인 패턴 읽기 전용 조회. 기존 P0 저장/제출 로직에는 영향이 없다. */
public interface PatternPredictionRepository extends Repository<Prediction, UUID> {

    @Query("""
            select new com.finsight.pattern.dto.PatternPredictionRow(
                s.id, s.newsId, n.category, p.horizon, p.direction, p.reason)
            from LearningSession s, SessionTarget st, Prediction p, News n
            where s.userId = :userId
              and s.submittedAt is not null
              and st.learningSessionId = s.id
              and st.isSelected = true
              and p.sessionTargetId = st.id
              and p.submittedAt is not null
              and p.direction is not null
              and n.id = s.newsId
              and (:fromInclusive is null or s.submittedAt >= :fromInclusive)
              and (:toExclusive is null or s.submittedAt < :toExclusive)
            order by s.submittedAt desc, st.sortOrder asc, p.horizon asc
            """)
    List<PatternPredictionRow> findSubmittedPredictionsForUser(
            @Param("userId") UUID userId,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive
    );

    default List<PatternPredictionRow> findSubmittedPredictionsForUser(UUID userId) {
        return findSubmittedPredictionsForUser(userId, null, null);
    }
}
