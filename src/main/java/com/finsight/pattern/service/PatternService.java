package com.finsight.pattern.service;

import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.pattern.dto.PatternReflectionRow;
import com.finsight.pattern.repository.PatternPredictionRepository;
import com.finsight.pattern.repository.PatternReflectionRepository;
import com.finsight.reflection.entity.ReflectionNote;
import com.finsight.prediction.entity.Prediction;
import com.finsight.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.finsight.pattern.dto.PatternResponses.*;

/**
 * 로그인 사용자의 제출된 학습 기록만을 읽어 규칙 기반 개인 패턴을 집계한다.
 * 방향의 의미를 추론하거나 예측 성과를 평가하거나 투자 행동을 권하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class PatternService {
    private static final List<Integer> HORIZONS = List.of(1, 5, 20);
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final Map<String, String> KNOWN_CATEGORY_NAMES = Map.of(
            "rate", "금리",
            "exchange_rate", "환율",
            "semiconductor", "반도체",
            "energy", "에너지",
            "price", "물가",
            "employment", "고용",
            "economy", "경제"
    );
    private static final int DEFAULT_REASON_LIMIT = 10;
    private static final int DEFAULT_SUGGESTION_LIMIT = 5;

    private final PatternPredictionRepository patternPredictionRepository;
    private final PatternReflectionRepository patternReflectionRepository;

    public PatternService(PatternPredictionRepository patternPredictionRepository,
                          PatternReflectionRepository patternReflectionRepository) {
        this.patternPredictionRepository = patternPredictionRepository;
        this.patternReflectionRepository = patternReflectionRepository;
    }

    public CategoriesResponse categories(UUID userId) {
        return categories(userId, null, null);
    }

    public CategoriesResponse categories(UUID userId, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "분석 기간이 올바르지 않습니다.");
        }

        OffsetDateTime fromInclusive = from == null ? null
                : from.atStartOfDay(KOREA_ZONE).toOffsetDateTime();

        OffsetDateTime toExclusive = to == null ? null
                : to.plusDays(1).atStartOfDay(KOREA_ZONE).toOffsetDateTime();

        List<PatternPredictionRow> rows =
                patternPredictionRepository.findSubmittedPredictionsForUser(
                        userId, fromInclusive, toExclusive);

        Map<String, DirectionCounter> counters = new HashMap<>();
        Map<String, Set<UUID>> sessionsByCategory = new HashMap<>();

        for (PatternPredictionRow row : rows) {
            counters.computeIfAbsent(row.newsCategory(), ignored -> new DirectionCounter())
                    .add(row.direction());
            sessionsByCategory.computeIfAbsent(row.newsCategory(), ignored -> new HashSet<>())
                    .add(row.sessionId());
        }

        List<CategoryItem> items = counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new CategoryItem(
                        entry.getKey(),
                        KNOWN_CATEGORY_NAMES.getOrDefault(entry.getKey(), entry.getKey()),
                        sessionsByCategory.get(entry.getKey()).size(),
                        entry.getValue().total(),
                        entry.getValue().counts()))
                .toList();
        long totalSessions = rows.stream().map(PatternPredictionRow::sessionId).distinct().count();
        return new CategoriesResponse(totalSessions, rows.size(), items);
    }

    public HorizonsResponse horizons(UUID userId) {
        return horizons(userId, null, null);
    }

    public HorizonsResponse horizons(UUID userId, LocalDate from, LocalDate to) {
        return horizons(userId, from, to, null);
    }

    public HorizonsResponse horizons(UUID userId, LocalDate from, LocalDate to, String category) {

        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "분석 기간이 올바르지 않습니다.");
        }
        if (category != null && (category.isBlank() || category.length() > 255)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "분석 조건이 올바르지 않습니다.");
        }

        OffsetDateTime fromInclusive = from == null ? null
                : from.atStartOfDay(KOREA_ZONE).toOffsetDateTime();

        OffsetDateTime toExclusive = to == null ? null
                : to.plusDays(1).atStartOfDay(KOREA_ZONE).toOffsetDateTime();

        List<PatternPredictionRow> rows =
                patternPredictionRepository.findSubmittedPredictionsForUser(
                        userId, fromInclusive, toExclusive);

        if (category != null) {
            rows = rows.stream()
                    .filter(row -> category.equals(row.newsCategory()))
                    .toList();
        }

        Map<Integer, DirectionCounter> counters = new LinkedHashMap<>();
        HORIZONS.forEach(h -> counters.put(h, new DirectionCounter()));

        for (PatternPredictionRow row : rows) {
            DirectionCounter counter = counters.get(row.horizon().intValue());
            if (counter != null) counter.add(row.direction());
        }

        List<HorizonItem> items = counters.entrySet().stream()
                .map(e -> new HorizonItem(
                        "T" + e.getKey(),
                        e.getKey(),
                        e.getValue().total(),
                        e.getValue().counts()))
                .toList();

        return new HorizonsResponse(rows.size(), items);
    }

    public ReasonsResponse reasons(UUID userId) {
        return reasons(userId, null, null, null);
    }

    public ReasonsResponse reasons(UUID userId, LocalDate from, LocalDate to, Integer limit) {
        validateDateRange(from, to);
        int effectiveLimit = limit == null ? DEFAULT_REASON_LIMIT : limit;
        validateLimit(effectiveLimit);

        // 예측은 세션 제출일, 최종 회고는 회고 작성일로 기간을 적용한다.
        OffsetDateTime fromInclusive = dayStart(from);
        OffsetDateTime toExclusive = nextDayStart(to);
        List<PatternPredictionRow> rows = patternPredictionRepository
                .findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive);
        List<PatternReflectionRow> missedNotes = patternReflectionRepository.findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, fromInclusive, toExclusive);

        long predictionCount = rows.stream().filter(row -> hasText(row.reason())).count();
        long reflectionCount = missedNotes.stream().filter(note -> hasText(note.body())).count();

        // 예측 건수(예: 한 뉴스의 T1/T5/T20 3건)가 아닌, 이유가 있는 뉴스 수로 판단한다.
        if (distinctReasonNews(rows) < 5) {
            return new ReasonsResponse(predictionCount, reflectionCount, List.of(), List.of());
        }

        List<VariablePattern> considered = PatternVariableAnalyzer.considered(rows);
        List<VariablePattern> missed = PatternVariableAnalyzer.missed(missedNotes);
        return new ReasonsResponse(predictionCount, reflectionCount,
                considered.stream().limit(effectiveLimit).toList(),
                missed.stream().limit(effectiveLimit).toList());
    }

    public SuggestionsResponse suggestions(UUID userId) {
        return suggestions(userId, null);
    }

    public SuggestionsResponse suggestions(UUID userId, Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_SUGGESTION_LIMIT : limit;
        validateLimit(effectiveLimit);

        List<PatternPredictionRow> rows = patternPredictionRepository
                .findSubmittedPredictionsForUser(userId, null, null);
        if (distinctReasonNews(rows) < 5) {
            return new SuggestionsResponse(true, List.of());
        }

        List<PatternReflectionRow> missedNotes = patternReflectionRepository.findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, null, null);
        List<VariablePattern> considered = PatternVariableAnalyzer.considered(rows);
        List<VariablePattern> missed = PatternVariableAnalyzer.missed(missedNotes);

        List<Suggestion> candidates = new ArrayList<>();
        for (VariablePattern pattern : considered) {
            if (pattern.mentionCount() >= 3) {
                candidates.add(new Suggestion(
                        "consider-other-perspectives-" + pattern.variable().replace('_', '-'),
                        pattern.variableName() + " 외의 변수도 함께 확인해 보세요.",
                        "여러 뉴스에서 " + pattern.variableName() + "를 반복적으로 고려했습니다. "
                                + "다음 학습에서는 다른 경제 변수도 함께 검토해 보세요.",
                        "서로 다른 뉴스 " + pattern.mentionCount() + "개에서 "
                                + pattern.variableName() + " 변수를 예측 근거로 언급함",
                        new SuggestionEvidence("considered_variable", pattern.mentionCount(),
                                pattern.exampleTexts())
                ));
            }
        }
        for (VariablePattern pattern : missed) {
            if (pattern.mentionCount() >= 2) {
                candidates.add(new Suggestion(
                        "consider-" + pattern.variable().replace('_', '-'),
                        pattern.variableName() + " 변수를 함께 고려해 보세요.",
                        "최종 회고에서 " + pattern.variableName() + "를 놓쳤다고 기록한 사례가 반복되었습니다. "
                                + "다음 학습에서는 해당 변수를 함께 확인해 보세요.",
                        "서로 다른 뉴스 " + pattern.mentionCount() + "개의 최종 회고에서 "
                                + pattern.variableName() + "를 놓친 변수로 언급함",
                        new SuggestionEvidence("missed_variable", pattern.mentionCount(),
                                pattern.exampleTexts())
                ));
            }
        }

        // 건수 내림차순, 동률 시 식별자 순으로 결정적으로 반환한다.
        candidates.sort(Comparator.comparingLong((Suggestion s) -> s.evidence().count())
                .reversed().thenComparing(Suggestion::suggestionId));
        return new SuggestionsResponse(false,
                candidates.stream().limit(effectiveLimit).toList());
    }

    private static long distinctReasonNews(List<PatternPredictionRow> rows) {
        return rows.stream()
                .filter(row -> hasText(row.reason()))
                .map(PatternPredictionRow::newsId)
                .filter(id -> id != null)
                .distinct()
                .count();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "조회 조건이 올바르지 않습니다.");
        }
    }

    private static void validateLimit(int limit) {
        if (limit <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "조회 조건이 올바르지 않습니다.");
        }
    }

    private static OffsetDateTime dayStart(LocalDate date) {
        return date == null ? null : date.atStartOfDay(KOREA_ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime nextDayStart(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(KOREA_ZONE).toOffsetDateTime();
    }

    private static final class DirectionCounter {
        private long up;
        private long down;
        private long neutral;

        void add(Prediction.Direction direction) {
            switch (direction) {
                case up -> up++;
                case down -> down++;
                case neutral -> neutral++;
            }
        }

        long total() {
            return up + down + neutral;
        }

        DirectionCounts counts() {
            return new DirectionCounts(up, down, neutral);
        }
    }
}
