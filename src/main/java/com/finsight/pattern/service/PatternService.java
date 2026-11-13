package com.finsight.pattern.service;

import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.pattern.repository.PatternPredictionRepository;
import com.finsight.prediction.entity.Prediction;
import com.finsight.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

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
    private static final int TERM_LIMIT = 10;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    // Minimal stop words; token frequency is lexical, NOT semantic concept extraction.
    private static final Set<String> STOP_WORDS = Set.of(
            "있다", "없다", "같다", "생각", "판단", "때문", "것으로", "것이다",
            "것을", "이후", "그리고", "하지만", "또한", "합니다", "했습니다", "있습니다",
            "예상합니다", "예측합니다", "예상됩니다", "가능성", "가능성이",
            "단기", "단기적으로", "중기", "중기적으로", "장기", "장기적으로",
            "there", "about", "with", "from", "would", "that", "this", "have", "will"
    );

    private final PatternPredictionRepository patternPredictionRepository;

    public PatternService(PatternPredictionRepository patternPredictionRepository) {
        this.patternPredictionRepository = patternPredictionRepository;
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
        List<PatternPredictionRow> rows = rowsFor(userId);
        Map<Integer, Long> totals = new HashMap<>();
        Map<Integer, Long> explained = new HashMap<>();
        Map<String, Long> termCounts = new HashMap<>();
        long withReason = 0;
        for (PatternPredictionRow row : rows) {
            int horizon = row.horizon().intValue();
            totals.merge(horizon, 1L, Long::sum);
            if (!hasReason(row.reason())) continue;
            withReason++;
            explained.merge(horizon, 1L, Long::sum);
            // One term counts once per prediction, no matter how often it occurs in the reason.
            for (String term : distinctTerms(row.reason())) {
                termCounts.merge(term, 1L, Long::sum);
            }
        }
        List<ReasonCoverage> byHorizon = HORIZONS.stream().map(h -> {
            long total = totals.getOrDefault(h, 0L);
            long provided = explained.getOrDefault(h, 0L);
            return new ReasonCoverage(h, total, provided, total - provided, percent(provided, total));
        }).toList();
        List<FrequentTerm> frequentTerms = termCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(TERM_LIMIT)
                .map(e -> new FrequentTerm(e.getKey(), e.getValue()))
                .toList();
        return new ReasonsResponse(rows.size(), withReason, rows.size() - withReason,
                percent(withReason, rows.size()), byHorizon, frequentTerms);
    }

    public SuggestionsResponse suggestions(UUID userId) {
        List<PatternPredictionRow> rows = rowsFor(userId);
        if (rows.isEmpty()) {
            return new SuggestionsResponse(0, List.of(new Suggestion(
                    "START_LEARNING", null, null,
                    "제출된 예측 기록이 없습니다. 학습 세션에서 예측을 제출한 뒤 패턴을 확인해 보세요.")));
        }

        List<Suggestion> suggestions = new ArrayList<>();
        // 임시 제안 규칙: 'news.category' 간 제출 건수가 다를 때만 덜 학습한 뉴스 분야를 안내한다.
        // P1 PDF에는 suggestions 상세 정책이 없어 FE/BE 확정이 필요하다.
        Map<String, Long> categoryCount = rows.stream()
                .collect(Collectors.groupingBy(PatternPredictionRow::newsCategory, Collectors.counting()));
        long maxCategoryCount = categoryCount.values().stream()
                .mapToLong(Long::longValue)
                .max().orElse(0L);
        categoryCount.entrySet().stream()
                .filter(entry -> entry.getValue() < maxCategoryCount)
                .min(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .ifPresent(entry -> suggestions.add(new Suggestion(
                        "EXPLORE_CATEGORY", entry.getKey(), null,
                        entry.getKey() + " 분야의 제출 예측 기록이 상대적으로 적습니다. 다음 학습에서 이 분야를 살펴보세요.")));

        Map<Integer, Long> totals = new HashMap<>();
        Map<Integer, Long> missing = new HashMap<>();
        for (PatternPredictionRow row : rows) {
            int horizon = row.horizon().intValue();
            totals.merge(horizon, 1L, Long::sum);
            if (!hasReason(row.reason())) missing.merge(horizon, 1L, Long::sum);
        }
        HORIZONS.stream()
                .filter(h -> totals.getOrDefault(h, 0L) > 0 && missing.getOrDefault(h, 0L) > 0)
                .max(Comparator.<Integer>comparingLong(h -> missing.getOrDefault(h, 0L))
                        .thenComparing(Comparator.reverseOrder()))
                .ifPresent(h -> suggestions.add(new Suggestion(
                        "ADD_REASON", null, h,
                        "T" + h + " 예측에서 판단 이유가 비어 있는 기록이 "
                                + missing.get(h) + "건 있습니다. 다음에는 이유도 함께 남겨 보세요.")));

        return new SuggestionsResponse(rows.size(), List.copyOf(suggestions));
    }

    private List<PatternPredictionRow> rowsFor(UUID userId) {
        return patternPredictionRepository.findSubmittedPredictionsForUser(userId);
    }

    private static boolean hasReason(String reason) {
        return reason != null && !reason.isBlank();
    }

    private static Set<String> distinctTerms(String reason) {
        Set<String> terms = new HashSet<>();
        Matcher matcher = TOKEN.matcher(reason);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            if (!STOP_WORDS.contains(word) && !word.chars().allMatch(Character::isDigit)) {
                terms.add(word);
            }
        }
        return terms;
    }

    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : Math.round(1000.0 * numerator / denominator) / 10.0;
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
