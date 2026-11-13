package com.finsight.pattern.dto;

import java.util.List;

/** P1 개인 패턴 응답 DTO. categories/horizons는 P1 PDF 23~27페이지 기준. */
public final class PatternResponses {
    private PatternResponses() {
    }

    public record DirectionCounts(long up, long down, long neutral) {
    }

    public record CategoryItem(
            String category,
            String categoryName,
            long sessionCount,
            long predictionCount,
            DirectionCounts directionCounts
    ) {
    }

    public record CategoriesResponse(
            long totalSessions,
            long totalPredictions,
            List<CategoryItem> categories
    ) {
    }

    public record HorizonItem(
            String horizon,
            int tradingDays,
            long predictionCount,
            DirectionCounts directionCounts
    ) {
    }

    public record HorizonsResponse(
            long totalPredictions,
            List<HorizonItem> horizons
    ) {
    }

    // 아래 두 API는 새 P1 PDF에 상세 응답 규격이 없어 기존 구현 계약을 유지한다.
    public record ReasonCoverage(
            int horizon,
            long totalPredictions,
            long withReason,
            long withoutReason,
            double coveragePercent
    ) {
    }

    public record FrequentTerm(String term, long predictionCount) {
    }

    public record ReasonsResponse(
            long analyzedPredictions,
            long withReason,
            long withoutReason,
            double coveragePercent,
            List<ReasonCoverage> byHorizon,
            List<FrequentTerm> frequentTerms
    ) {
    }

    public record Suggestion(
            String code,
            String category,
            Integer horizon,
            String message
    ) {
    }

    public record SuggestionsResponse(long analyzedPredictions, List<Suggestion> suggestions) {
    }
}
