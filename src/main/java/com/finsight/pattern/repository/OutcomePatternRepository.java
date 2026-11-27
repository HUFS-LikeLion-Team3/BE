package com.finsight.pattern.repository;

import com.finsight.pattern.dto.OutcomePatternRow;
import com.finsight.pattern.dto.OutcomeReflectionRow;
import com.finsight.prediction.entity.Prediction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 관측 결과 사례 비교용 읽기 전용 조회. 기존 저장 로직과 스키마는 건드리지 않는다. */
public interface OutcomePatternRepository extends Repository<Prediction, UUID> {

    @Query("""
            select new com.finsight.pattern.dto.OutcomePatternRow(
                s.id, st.id, s.newsId, n.title, mt.code, mt.displayName, mo.unit, st.sortOrder,
                p.horizon, p.direction, p.reason,
                b.baselineSessionDate, b.baselineValue,
                mo.observationSessionDate, mo.observedValue, mo.changeValue, mo.changePercent,
                s.submittedAt, s.sessionType, s.resultsRevealedAt)
            from LearningSession s, SessionTarget st, Prediction p, MarketTarget mt,
                 MarketObservation mo, SessionTargetBaseline b, News n
            where s.userId = :userId
              and s.submittedAt is not null
              and st.learningSessionId = s.id
              and st.isSelected = true
              and mt.id = st.marketTargetId
              and mt.marketCategory = :market
              and p.sessionTargetId = st.id
              and p.submittedAt is not null
              and p.direction is not null
              and p.horizon = :horizon
              and mo.sessionTargetId = st.id
              and mo.horizon = :horizonValue
              and mo.status = com.finsight.outcome.entity.MarketObservation.OutcomeStatus.ready
              and b.id = mo.baselineId
              and n.id = s.newsId
            order by s.submittedAt desc, st.sortOrder asc
            """)
    List<OutcomePatternRow> findReadyOutcomes(
            @Param("userId") UUID userId,
            @Param("market") String market,
            @Param("horizon") Short horizon,
            @Param("horizonValue") Integer horizonValue
    );

    @Query("""
            select new com.finsight.pattern.dto.OutcomeReflectionRow(
                r.learningSessionId, r.sessionTargetId, r.promptKey, r.body)
            from ReflectionNote r
            where r.learningSessionId in :sessionIds
              and r.reflectionType = 'final'
            order by r.createdAt asc, r.id asc
            """)
    List<OutcomeReflectionRow> findFinalReflections(@Param("sessionIds") Collection<UUID> sessionIds);
}
