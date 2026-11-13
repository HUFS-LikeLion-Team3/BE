package com.finsight.pattern.service;

import com.finsight.pattern.dto.PatternPredictionRow;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.finsight.pattern.dto.PatternResponses.*;

/**
 * Transparent descriptive statistics only. These are NOT performance scores,
 * AI-based interpretations, or investment recommendations.
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
        validateLimit(limit == null ? DEFAULT_REASON_LIMIT : limit);

        // 기존 예측 조회는 세션 제출일을 기준으로 기간을 적용한다.
        // 최종 회고는 실제 회고 작성일(createdAt) 기준으로 별도 조회한다.
        OffsetDateTime fromInclusive = dayStart(from);
        OffsetDateTime toExclusive = nextDayStart(to);
        List<PatternPredictionRow> rows = patternPredictionRepository
                .findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive);
        List<ReflectionNote> missedNotes = patternReflectionRepository.findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, fromInclusive, toExclusive);

        long predictionCount = rows.stream()
                .filter(row -> hasText(row.reason()))
                .count();
        long reflectionCount = missedNotes.stream()
                .filter(note -> hasText(note.getBody()))
                .count();

        // P1 명세는 변수 추출/동의어 정규화 규칙을 별도로 확정하도록 명시한다.
        // 확정 전에는 단어를 경제 변수인 것처럼 추측하거나 가짜 변수 코드를 생성하지 않는다.
        return new ReasonsResponse(predictionCount, reflectionCount, List.of(), List.of());
    }

    public SuggestionsResponse suggestions(UUID userId) {
        return suggestions(userId, null);
    }

    public SuggestionsResponse suggestions(UUID userId, Integer limit) {
        validateLimit(limit == null ? DEFAULT_SUGGESTION_LIMIT : limit);

        // 데이터 충분성 기준과 추천 생성 규칙은 P1 명세에서 미확정이다.
        // 규칙이 확정되기 전에는 개인화 추천과 근거를 지어내지 않는다.
        return new SuggestionsResponse(true, List.of());
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
