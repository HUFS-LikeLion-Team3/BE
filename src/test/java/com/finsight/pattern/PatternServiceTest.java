package com.finsight.pattern;

import com.finsight.global.exception.ApiException;
import com.finsight.pattern.dto.PatternPredictionRow;
import com.finsight.pattern.repository.PatternPredictionRepository;
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

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionA = UUID.randomUUID();
    private final UUID sessionB = UUID.randomUUID();

    private PatternService service() {
        return new PatternService(rows);
    }

    private PatternPredictionRow row(UUID session, String newsCategory,
                                     int horizon, Prediction.Direction direction, String reason) {
        return new PatternPredictionRow(session, newsCategory, (short) horizon, direction, reason);
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
    void reasonsCountBlankAsMissingAndEachTermOncePerPrediction() {
        when(rows.findSubmittedPredictionsForUser(userId)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, "환율 환율 상승"),
                row(sessionA, "rate", 5, Prediction.Direction.down, "환율 하락"),
                row(sessionA, "rate", 20, Prediction.Direction.neutral, "  "),
                row(sessionB, "semiconductor", 1, Prediction.Direction.up, null)
        ));
        var result = service().reasons(userId);
        assertEquals(4, result.analyzedPredictions());
        assertEquals(2, result.withReason());
        assertEquals(2, result.withoutReason());
        assertEquals(50.0, result.coveragePercent());
        assertEquals("환율", result.frequentTerms().getFirst().term());
        assertEquals(2, result.frequentTerms().getFirst().predictionCount());
    }

    @Test
    void suggestionsWithNoSubmittedHistoryReturnStartLearning() {
        when(rows.findSubmittedPredictionsForUser(userId)).thenReturn(List.of());
        var result = service().suggestions(userId);
        assertEquals(0, result.analyzedPredictions());
        assertEquals(1, result.suggestions().size());
        assertEquals("START_LEARNING", result.suggestions().getFirst().code());
    }

    @Test
    void suggestionsDoNotCompareNewsCategoriesWithMarketCategories() {
        when(rows.findSubmittedPredictionsForUser(userId)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, "금리"),
                row(sessionA, "rate", 5, Prediction.Direction.up, "금리"),
                row(sessionA, "rate", 20, Prediction.Direction.down, "금리")
        ));
        var result = service().suggestions(userId);
        assertTrue(result.suggestions().isEmpty());
    }

    @Test
    void provisionalSuggestionsUseOnlyObservedNewsCategoriesAndMissingReasons() {
        when(rows.findSubmittedPredictionsForUser(userId)).thenReturn(List.of(
                row(sessionA, "rate", 1, Prediction.Direction.up, null),
                row(sessionA, "rate", 5, Prediction.Direction.down, "금리"),
                row(sessionA, "rate", 20, Prediction.Direction.down, null),
                row(sessionA, "rate", 20, Prediction.Direction.up, null),
                row(sessionB, "semiconductor", 1, Prediction.Direction.up, "실적")
        ));
        var result = service().suggestions(userId);
        assertEquals(2, result.suggestions().size());
        assertEquals("EXPLORE_CATEGORY", result.suggestions().get(0).code());
        assertEquals("semiconductor", result.suggestions().get(0).category());
        assertEquals("ADD_REASON", result.suggestions().get(1).code());
        assertEquals(20, result.suggestions().get(1).horizon());
    }
}
