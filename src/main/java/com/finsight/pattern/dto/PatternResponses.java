package com.finsight.pattern.dto;

import java.util.List;

/** P1 개인 패턴 API 응답 구조. */
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

    /** 예측 근거/회고 원문에서 사전으로 추출한 변수별 뉴스 단위 집계. */
    public record VariablePattern(
            String variable,
            String variableName,
            long mentionCount,
            List<String> exampleTexts
    ) {
    }

    public record ReasonsResponse(
            long analyzedPredictionCount,
            long analyzedReflectionCount,
            List<VariablePattern> consideredVariables,
            List<VariablePattern> missedVariables
    ) {
    }

    public record SuggestionEvidence(String type, long count, List<String> exampleTexts) {
    }

    public record Suggestion(
            String suggestionId,
            String title,
            String description,
            String reason,
            SuggestionEvidence evidence
    ) {
    }

    public record SuggestionsResponse(
            boolean insufficientData,
            List<Suggestion> suggestions
    ) {
    }
}
