package com.finsight.pattern.service;

import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.pattern.dto.PatternReflectionRow;
import com.finsight.pattern.dto.PatternResponses.VariablePattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P1에 합의된 최소한의 사전 기반 변수 추출. 원문 존재 여부만 판정하며
 * 방향, 인과관계, 예측의 정오 여부, 언급하지 않은 변수는 추론하지 않는다.
 * 사전 추가/변수 코드 변경 시 FE와 API 계약을 함께 수정해야 한다.
 */
final class PatternVariableAnalyzer {
    private static final int MAX_EXAMPLES = 3;

    private static final List<VariableDefinition> DICTIONARY = List.of(
            new VariableDefinition("interest_rate", "금리", List.of("기준금리", "금리 인상", "금리")),
            new VariableDefinition("exchange_rate", "환율", List.of("환율")),
            new VariableDefinition("prices", "물가", List.of("물가")),
            new VariableDefinition("employment", "고용", List.of("고용")),
            new VariableDefinition("earnings", "실적", List.of("실적")),
            new VariableDefinition("supply_demand", "수급", List.of("수급")),
            new VariableDefinition("commodities", "원자재", List.of("원자재"))
    );

    private PatternVariableAnalyzer() {
    }

    static List<VariablePattern> considered(List<PatternPredictionRow> predictions) {
        return aggregate(predictions.stream()
                .map(row -> new NewsText(row.newsId(), row.reason()))
                .toList());
    }

    static List<VariablePattern> missed(List<PatternReflectionRow> reflections) {
        return aggregate(reflections.stream()
                .map(row -> new NewsText(row.newsId(), row.body()))
                .toList());
    }

    private static List<VariablePattern> aggregate(List<NewsText> records) {
        Map<String, VariableCounter> counters = new LinkedHashMap<>();
        for (NewsText record : records) {
            if (record.newsId() == null || record.text() == null || record.text().isBlank()) {
                continue;
            }
            for (VariableDefinition definition : DICTIONARY) {
                if (definition.terms().stream().anyMatch(record.text()::contains)) {
                    counters.computeIfAbsent(definition.code(), ignored -> new VariableCounter(definition))
                            .add(record.newsId(), record.text());
                }
            }
        }

        List<VariablePattern> result = new ArrayList<>();
        for (VariableCounter counter : counters.values()) {
            result.add(counter.pattern());
        }
        result.sort(Comparator.comparingLong(VariablePattern::mentionCount).reversed()
                .thenComparing(VariablePattern::variable));
        return List.copyOf(result);
    }

    private record VariableDefinition(String code, String displayName, List<String> terms) {
    }

    private record NewsText(UUID newsId, String text) {
    }

    private static final class VariableCounter {
        private final VariableDefinition definition;
        // 동일 뉴스의 여러 예측/반복 언급은 변수별 1건으로 집계.
        private final Map<UUID, String> examplesByNews = new LinkedHashMap<>();

        private VariableCounter(VariableDefinition definition) {
            this.definition = definition;
        }

        private void add(UUID newsId, String originalText) {
            examplesByNews.putIfAbsent(newsId, originalText);
        }

        private VariablePattern pattern() {
            return new VariablePattern(definition.code(), definition.displayName(), examplesByNews.size(),
                    examplesByNews.values().stream().limit(MAX_EXAMPLES).toList());
        }
    }
}
