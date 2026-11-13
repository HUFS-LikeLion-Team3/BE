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

    /** 변수 추출 규칙이 합의되면 예측/회고에서 확인된 변수만 채운다. */
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

    public record SuggestionEvidence(String type, long count) {
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
