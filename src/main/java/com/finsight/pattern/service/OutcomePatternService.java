package com.finsight.pattern.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.pattern.dto.OutcomePatternResponse;
import com.finsight.pattern.dto.OutcomePatternResponse.OutcomeCase;
import com.finsight.pattern.dto.OutcomePatternRow;
import com.finsight.pattern.dto.OutcomeReflectionRow;
import com.finsight.pattern.repository.OutcomePatternRepository;
import com.finsight.reflection.entity.ReflectionNote;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 같은 시장 범주·기간에서 확정 관측값이 있는 서로 다른 뉴스의 사례를 나란히 보여준다.
 * 예측의 정오, 점수, 승률은 계산하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class OutcomePatternService {
    static final int MINIMUM_NEWS = 3;
    static final int REPEATED_NEWS = 5;
    private static final Set<Integer> HORIZONS = Set.of(1, 5, 20);
    private static final List<ReflectionNote.PromptKey> REFLECTION_ORDER = List.of(
            ReflectionNote.PromptKey.learned,
            ReflectionNote.PromptKey.missed_variable,
            ReflectionNote.PromptKey.next_check);

    private final OutcomePatternRepository repository;

    public OutcomePatternService(OutcomePatternRepository repository) {
        this.repository = repository;
    }

    public OutcomePatternResponse outcomes(UUID userId, String market, Integer horizon) {
        if (market == null || market.isBlank() || market.length() > 255
                || horizon == null || !HORIZONS.contains(horizon)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "분석 조건이 올바르지 않습니다.");
        }
        String marketCode = market.trim();

        // 뉴스당 한 사례: 가장 최근에 제출한 세션의 첫 번째 대상.
        Map<UUID, OutcomePatternRow> byNews = new LinkedHashMap<>();
        for (OutcomePatternRow row : repository.findReadyOutcomes(
                userId, marketCode, horizon.shortValue(), horizon)) {
            if (!resultsVisible(row)) {
                continue;
            }
            byNews.putIfAbsent(row.newsId(), row);
        }

        long distinct = byNews.size();
        String stage = distinct >= REPEATED_NEWS ? "repeated_tendency"
                : distinct >= MINIMUM_NEWS ? "initial_observation" : "insufficient";
        if (distinct < MINIMUM_NEWS) {
            return new OutcomePatternResponse(marketCode, horizon, distinct, MINIMUM_NEWS, REPEATED_NEWS, stage, List.of());
        }

        List<UUID> sessionIds = byNews.values().stream().map(OutcomePatternRow::sessionId).distinct().toList();
        Map<UUID, List<OutcomeReflectionRow>> reflections = new LinkedHashMap<>();
        for (OutcomeReflectionRow note : repository.findFinalReflections(sessionIds)) {
            reflections.computeIfAbsent(note.sessionId(), ignored -> new java.util.ArrayList<>()).add(note);
        }

        List<OutcomeCase> cases = byNews.values().stream()
                .map(row -> toCase(row, reflections.getOrDefault(row.sessionId(), List.of())))
                .toList();
        return new OutcomePatternResponse(marketCode, horizon, distinct, MINIMUM_NEWS, REPEATED_NEWS, stage, cases);
    }

    /** Replay는 결과 공개 후에만 관측값을 보여준다(OutcomeService와 같은 기준). */
    private static boolean resultsVisible(OutcomePatternRow row) {
        return row.sessionType() != LearningSession.SessionType.replay || row.resultsRevealedAt() != null;
    }

    private static OutcomeCase toCase(OutcomePatternRow row, List<OutcomeReflectionRow> notes) {
        return new OutcomeCase(
                row.sessionId(), row.newsId(), row.newsTitle(), row.targetCode(), row.targetName(), row.unit(),
                row.direction().name(), row.reason(),
                row.baselineDate(), row.baselineValue(),
                row.observationDate(), row.observedValue(), row.changeValue(), row.changePercent(),
                pickReflection(row, notes));
    }

    /** 이 대상에 쓴 회고 또는 세션 전체 회고 중 learned → missed_variable → next_check 순으로 하나. */
    private static String pickReflection(OutcomePatternRow row, List<OutcomeReflectionRow> notes) {
        return notes.stream()
                .filter(note -> note.body() != null && !note.body().isBlank())
                .filter(note -> note.sessionTargetId() == null || note.sessionTargetId().equals(row.sessionTargetId()))
                .filter(note -> REFLECTION_ORDER.contains(note.promptKey()))
                .min(Comparator.comparingInt((OutcomeReflectionRow note) -> REFLECTION_ORDER.indexOf(note.promptKey()))
                        .thenComparing(note -> note.sessionTargetId() == null ? 1 : 0))
                .map(OutcomeReflectionRow::body)
                .orElse(null);
    }
}
