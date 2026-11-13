package com.finsight.pattern.repository;

import com.finsight.reflection.entity.ReflectionNote;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 로그인 사용자의 최종 회고 중 missed_variable 응답만 읽는다. */
public interface PatternReflectionRepository extends Repository<ReflectionNote, UUID> {

    @Query("""
            select r from ReflectionNote r, LearningSession s
            where r.learningSessionId = s.id
              and s.userId = :userId
              and s.submittedAt is not null
              and s.completedAt is not null
              and r.reflectionType = 'final'
              and r.promptKey = :promptKey
              and (:fromInclusive is null or r.createdAt >= :fromInclusive)
              and (:toExclusive is null or r.createdAt < :toExclusive)
            order by r.createdAt desc, r.id asc
            """)
    List<ReflectionNote> findFinalMissedVariableNotes(
            @Param("userId") UUID userId,
            @Param("promptKey") ReflectionNote.PromptKey promptKey,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive
    );
}
