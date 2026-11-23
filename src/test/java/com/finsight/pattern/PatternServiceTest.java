package com.finsight.pattern;

import com.finsight.global.exception.ApiException;
import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.pattern.dto.PatternReflectionRow;
import com.finsight.pattern.repository.PatternPredictionRepository;
import com.finsight.pattern.repository.PatternReflectionRepository;
import com.finsight.reflection.entity.ReflectionNote;
import com.finsight.pattern.service.PatternService;
import com.finsight.prediction.entity.Prediction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatternServiceTest {

    @Mock PatternPredictionRepository rows;
    @Mock PatternReflectionRepository reflections;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionA = UUID.randomUUID();
    private final UUID sessionB = UUID.randomUUID();

    private PatternService service() {
        return new PatternService(rows, reflections);
    }

    private PatternPredictionRow row(UUID session, String newsCategory,
                                     int horizon, Prediction.Direction direction, String reason) {
        return row(session, session, newsCategory, horizon, direction, reason);
    }

    private PatternPredictionRow row(UUID session, UUID newsId, String newsCategory,
                                     int horizon, Prediction.Direction direction, String reason) {
        return new PatternPredictionRow(session, newsId, newsCategory, (short) horizon, direction, reason);
    }

    private PatternReflectionRow reflection(UUID newsId, String body) {
        return new PatternReflectionRow(newsId, body);
    }

    @Test
    void categoriesCountSubmittedPredictionsByNewsCategoryAndDistinctSessions() {
        UUID sessionC = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, null),
                row(sessionA, "rate", 5, Prediction.Direction.down, "금리 상승"),
                row(sessionB, "rate", 1, Prediction.Direction.up, "금리 상승"),
                row(sessionC, "semiconductor", 20, Prediction.Direction.neutral, "반도체 수요")
        ));
        var result = service().categories(userId);
        assertEquals(4, result.totalPredictions());
        assertEquals(3, result.totalSessions());
        assertEquals(List.of("rate", "semiconductor"),
                result.categories().stream().map(item -> item.category()).toList());
        var rate = result.categories().getFirst();
        assertEquals("금리", rate.categoryName());
        assertEquals(2, rate.sessionCount());
        assertEquals(3, rate.predictionCount());
        assertEquals(2, rate.directionCounts().up());
        assertEquals(1, rate.directionCounts().down());
        var semiconductor = result.categories().get(1);
        assertEquals("반도체", semiconductor.categoryName());
        assertEquals(1, semiconductor.sessionCount());
        assertEquals(1, semiconductor.directionCounts().neutral());
    }

    @Test
    void unknownNewsCategoryUsesOriginalCodeWithoutInventingLabel() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(sessionA, "other_custom", 1, Prediction.Direction.up, null)
        ));
        var result = service().categories(userId);
        assertEquals("other_custom", result.categories().getFirst().categoryName());
    }

    @Test
    void categoriesReturnZeroAndEmptyListWhenNoHistory() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of());
        var result = service().categories(userId);
        assertEquals(0, result.totalPredictions());
        assertEquals(0, result.totalSessions());
        assertTrue(result.categories().isEmpty());
    }

    @Test
    void horizonsAlwaysProvideT1T5T20AndZeroWhenNoHistory() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of());
        var result = service().horizons(userId);
        assertEquals(0, result.totalPredictions());
        assertEquals(List.of("T1", "T5", "T20"),
                result.horizons().stream().map(h -> h.horizon()).toList());
        assertEquals(List.of(1, 5, 20),
                result.horizons().stream().map(h -> h.tradingDays()).toList());
        assertTrue(result.horizons().stream().allMatch(h -> h.predictionCount() == 0));
        assertTrue(result.horizons().stream().allMatch(h -> h.directionCounts().up() == 0));
    }

    @Test
    void horizonsFilterNewsCategoryAfterFetchingSubmittedRows() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, "a"),
                row(sessionA, "rate", 5, Prediction.Direction.neutral, "b"),
                row(sessionB, "semiconductor", 1, Prediction.Direction.down, "c")
        ));
        var result = service().horizons(userId, null, null, "rate");
        assertEquals(2, result.totalPredictions());
        assertEquals(1, result.horizons().getFirst().directionCounts().up());
        assertEquals(0, result.horizons().getFirst().directionCounts().down());
        assertEquals(1, result.horizons().get(1).directionCounts().neutral());
    }

    @Test
    void dateFiltersForwardSeoulDayBoundsToRepository() {
        var from = LocalDate.of(2026, 11, 1);
        var to = LocalDate.of(2026, 11, 13);
        var zone = ZoneId.of("Asia/Seoul");
        OffsetDateTime fromInclusive = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        when(rows.findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive))
                .thenReturn(List.of(row(sessionA, "rate", 1, Prediction.Direction.up, null)));
        assertEquals(1, service().categories(userId, from, to).totalPredictions());
        assertEquals(1, service().horizons(userId, from, to, null).totalPredictions());
        verify(rows, times(2)).findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive);
    }

    @Test
    void invalidDatesAndBlankCategoryFailBeforeDatabaseAccess() {
        LocalDate late = LocalDate.of(2026, 12, 31);
        LocalDate early = LocalDate.of(2026, 1, 1);
        assertThrows(ApiException.class, () -> service().categories(userId, late, early));
        assertThrows(ApiException.class, () -> service().horizons(userId, late, early, null));
        assertThrows(ApiException.class, () -> service().horizons(userId, null, null, ""));
        assertThrows(ApiException.class, () -> service().horizons(userId, null, null, " "));
        assertThrows(ApiException.class, () -> service().horizons(userId, null, null, "x".repeat(256)));
        verifyNoInteractions(rows);
    }

    @Test
    void reasonsCountActualReasonAndFinalMissedVariableRecordsWithoutInventingVariables() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, "환율 상승"),
                row(sessionA, "rate", 5, Prediction.Direction.down, "  "),
                row(sessionB, "rate", 20, Prediction.Direction.neutral, null)
        ));
        when(reflections.findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, null, null))
                .thenReturn(List.of(reflection(sessionA, "환율 변화를 놓쳤다")));

        var result = service().reasons(userId, null, null, 10);
        assertEquals(1, result.analyzedPredictionCount());
        assertEquals(1, result.analyzedReflectionCount());
        assertTrue(result.consideredVariables().isEmpty());
        assertTrue(result.missedVariables().isEmpty());
    }

    @Test
    void reasonsUseOwnDateBoundariesForPredictionAndFinalReflectionLookups() {
        var from = LocalDate.of(2026, 11, 1);
        var to = LocalDate.of(2026, 11, 13);
        var zone = ZoneId.of("Asia/Seoul");
        OffsetDateTime fromInclusive = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toExclusive = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        when(rows.findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive))
                .thenReturn(List.of());
        when(reflections.findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, fromInclusive, toExclusive))
                .thenReturn(List.of());

        var result = service().reasons(userId, from, to, 1);
        assertEquals(0, result.analyzedPredictionCount());
        assertEquals(0, result.analyzedReflectionCount());
        verify(rows).findSubmittedPredictionsForUser(userId, fromInclusive, toExclusive);
        verify(reflections).findFinalMissedVariableNotes(
                userId, ReflectionNote.PromptKey.missed_variable, fromInclusive, toExclusive);
    }

    @Test
    void reasonsRejectInvalidDateAndLimitBeforeReadingRecords() {
        var late = LocalDate.of(2026, 12, 31);
        var early = LocalDate.of(2026, 1, 1);
        assertThrows(ApiException.class, () -> service().reasons(userId, late, early, 10));
        assertThrows(ApiException.class, () -> service().reasons(userId, null, null, 0));
        assertThrows(ApiException.class, () -> service().reasons(userId, null, null, -1));
        verifyNoInteractions(rows, reflections);
    }

    @Test
    void suggestionsReturnInsufficientDataWhenThereIsNoHistory() {
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of());
        var result = service().suggestions(userId, 5);
        assertTrue(result.insufficientData());
        assertTrue(result.suggestions().isEmpty());
        verifyNoInteractions(reflections);
    }

    @Test
    void suggestionsRejectInvalidLimit() {
        assertThrows(ApiException.class, () -> service().suggestions(userId, 0));
        assertThrows(ApiException.class, () -> service().suggestions(userId, -2));
        verifyNoInteractions(rows, reflections);
    }

    @Test
    void reasonsGateUsesDistinctNewsRatherThanNumberOfHorizonPredictions() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, "금리"),
                row(n1, "rate", 5, Prediction.Direction.down, "금리 금리"),
                row(n1, "rate", 20, Prediction.Direction.up, "기준금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "금리"),
                row(n3, "rate", 1, Prediction.Direction.up, "금리"),
                row(n4, "rate", 1, Prediction.Direction.up, "금리")
        ));
        when(reflections.findFinalMissedVariableNotes(userId, ReflectionNote.PromptKey.missed_variable,
                null, null)).thenReturn(List.of(reflection(n1, "환율을 놓침")));

        var result = service().reasons(userId, null, null, 10);
        assertEquals(6, result.analyzedPredictionCount());
        assertEquals(1, result.analyzedReflectionCount());
        assertTrue(result.consideredVariables().isEmpty());
        assertTrue(result.missedVariables().isEmpty());
    }

    @Test
    void reasonsNormalizeSevenTermsAndDeduplicateByNewsKeepingOriginalExamples() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        UUID n5 = UUID.randomUUID();
        String original = "기준금리 인상과 금리 동결을 함께 비교했습니다.";
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, original),
                row(n1, "rate", 5, Prediction.Direction.down, "금리 금리 금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "금리가 핵심"),
                row(n2, "rate", 5, Prediction.Direction.up, "기준금리"),
                row(n3, "rate", 1, Prediction.Direction.up, "환율 물가 고용"),
                row(n4, "rate", 1, Prediction.Direction.up, "실적 수급"),
                row(n5, "rate", 1, Prediction.Direction.up, "원자재 가격과 금리")
        ));
        String missedOriginal = "환율 변동을 고려하지 못했습니다.";
        when(reflections.findFinalMissedVariableNotes(userId, ReflectionNote.PromptKey.missed_variable,
                null, null)).thenReturn(List.of(
                reflection(n3, missedOriginal),
                reflection(n4, "환율 환율 변화를 놓침"),
                reflection(n5, "환율과 물가를 놓침")
        ));

        var result = service().reasons(userId, null, null, 10);
        assertEquals(7, result.analyzedPredictionCount());
        assertEquals(3, result.analyzedReflectionCount());
        assertEquals(7, result.consideredVariables().size());
        var rate = result.consideredVariables().getFirst();
        assertEquals("interest_rate", rate.variable());
        assertEquals("금리", rate.variableName());
        assertEquals(3, rate.mentionCount());
        assertEquals(original, rate.exampleTexts().getFirst());
        assertEquals(3, rate.exampleTexts().size());
        assertEquals(3, result.missedVariables().getFirst().mentionCount());
        assertEquals("exchange_rate", result.missedVariables().getFirst().variable());
        assertEquals(missedOriginal, result.missedVariables().getFirst().exampleTexts().getFirst());
        assertEquals(1, result.missedVariables().get(1).mentionCount());
        assertEquals("prices", result.missedVariables().get(1).variable());

        var limited = service().reasons(userId, null, null, 1);
        assertEquals(1, limited.consideredVariables().size());
        assertEquals(1, limited.missedVariables().size());
    }

    @Test
    void reasonsDoNotTreatMissedOnlyVariablesAsConsideredOrScoreDirections() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        UUID n5 = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, "기업 뉴스가 중요"),
                row(n2, "rate", 1, Prediction.Direction.down, "기술적인 관점"),
                row(n3, "rate", 1, Prediction.Direction.neutral, "모멘텀 중심"),
                row(n4, "rate", 1, Prediction.Direction.up, "시장 상황"),
                row(n5, "rate", 1, Prediction.Direction.down, "결과 예측")
        ));
        when(reflections.findFinalMissedVariableNotes(userId, ReflectionNote.PromptKey.missed_variable,
                null, null)).thenReturn(List.of(
                reflection(n1, "환율을 놓쳤습니다"),
                reflection(n2, "환율 변화를 놓쳤습니다")
        ));

        var result = service().reasons(userId);
        assertTrue(result.consideredVariables().isEmpty());
        assertEquals("exchange_rate", result.missedVariables().getFirst().variable());
        assertEquals(2, result.missedVariables().getFirst().mentionCount());
    }

    @Test
    void suggestionsTreatFourNewsWithManyHorizonRecordsAsInsufficient() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, "금리"),
                row(n1, "rate", 5, Prediction.Direction.up, "금리"),
                row(n1, "rate", 20, Prediction.Direction.up, "금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "금리"),
                row(n3, "rate", 1, Prediction.Direction.up, "금리"),
                row(n4, "rate", 1, Prediction.Direction.up, "금리")
        ));
        var result = service().suggestions(userId, 5);
        assertTrue(result.insufficientData());
        assertTrue(result.suggestions().isEmpty());
        verifyNoInteractions(reflections);
    }

    @Test
    void suggestionsWithEnoughNewsButNoRepeatingVariablesReturnEmptyWithFalseFlag() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        UUID n5 = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, "금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "환율"),
                row(n3, "rate", 1, Prediction.Direction.up, "물가"),
                row(n4, "rate", 1, Prediction.Direction.up, "고용"),
                row(n5, "rate", 1, Prediction.Direction.up, "실적")
        ));
        when(reflections.findFinalMissedVariableNotes(userId, ReflectionNote.PromptKey.missed_variable,
                null, null)).thenReturn(List.of());
        var result = service().suggestions(userId, 5);
        assertFalse(result.insufficientData());
        assertTrue(result.suggestions().isEmpty());
    }

    @Test
    void suggestionsBuildOnlyTwoAgreedRuleTypesWithRealEvidenceAndApplyLimit() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        UUID n5 = UUID.randomUUID();
        String original = "기준금리를 중요하게 봤습니다";
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, original),
                row(n1, "rate", 5, Prediction.Direction.up, "금리 금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "금리 상승"),
                row(n3, "rate", 1, Prediction.Direction.up, "금리 동결"),
                row(n4, "rate", 1, Prediction.Direction.up, "기술적 지표"),
                row(n5, "rate", 1, Prediction.Direction.up, "모멘텀")
        ));
        String missed = "환율 변화를 놓쳤습니다";
        when(reflections.findFinalMissedVariableNotes(userId, ReflectionNote.PromptKey.missed_variable,
                null, null)).thenReturn(List.of(reflection(n2, missed), reflection(n4, "환율 환율")));

        var result = service().suggestions(userId, 5);
        assertFalse(result.insufficientData());
        assertEquals(2, result.suggestions().size());
        var considered = result.suggestions().getFirst();
        assertEquals("consider-other-perspectives-interest-rate", considered.suggestionId());
        assertEquals("considered_variable", considered.evidence().type());
        assertEquals(3, considered.evidence().count());
        assertEquals(original, considered.evidence().exampleTexts().getFirst());
        var missedSuggestion = result.suggestions().get(1);
        assertEquals("consider-exchange-rate", missedSuggestion.suggestionId());
        assertEquals("missed_variable", missedSuggestion.evidence().type());
        assertEquals(2, missedSuggestion.evidence().count());
        assertEquals(missed, missedSuggestion.evidence().exampleTexts().getFirst());

        var limited = service().suggestions(userId, 1);
        assertEquals(1, limited.suggestions().size());
        assertEquals(considered.suggestionId(), limited.suggestions().getFirst().suggestionId());
    }

    @Test
    void blankReasonsDoNotSatisfyFiveNewsThreshold() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID n3 = UUID.randomUUID();
        UUID n4 = UUID.randomUUID();
        UUID n5 = UUID.randomUUID();
        when(rows.findSubmittedPredictionsForUser(userId, null, null)).thenReturn(List.of(
                row(n1, "rate", 1, Prediction.Direction.up, "금리"),
                row(n2, "rate", 1, Prediction.Direction.up, "금리"),
                row(n3, "rate", 1, Prediction.Direction.up, "금리"),
                row(n4, "rate", 1, Prediction.Direction.up, "금리"),
                row(n5, "rate", 1, Prediction.Direction.up, "   ")
        ));
        var result = service().suggestions(userId);
        assertTrue(result.insufficientData());
        assertTrue(result.suggestions().isEmpty());
    }

}
