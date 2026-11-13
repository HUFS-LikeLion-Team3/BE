package com.finsight.pattern;

import com.finsight.global.security.CurrentUser;
import com.finsight.pattern.controller.PatternController;
import com.finsight.pattern.dto.PatternResponses;
import com.finsight.pattern.service.PatternService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatternControllerTest {
    @Mock CurrentUser currentUser;
    @Mock PatternService service;

    @Test
    void everyEndpointUsesTheAuthenticatedUserIdNeverAClientSuppliedId() {
        UUID owner = UUID.randomUUID();
        when(currentUser.id()).thenReturn(owner);

        var categories = new PatternResponses.CategoriesResponse(0, 0, List.of());
        var horizons = new PatternResponses.HorizonsResponse(0, List.of());
        var reasons = new PatternResponses.ReasonsResponse(0, 0, 0, 0.0, List.of(), List.of());
        var suggestions = new PatternResponses.SuggestionsResponse(0, List.of());
        when(service.categories(owner, null, null)).thenReturn(categories);
        when(service.horizons(owner, null, null, null)).thenReturn(horizons);
        when(service.reasons(owner)).thenReturn(reasons);
        when(service.suggestions(owner)).thenReturn(suggestions);

        var controller = new PatternController(currentUser, service);
        assertSame(categories, controller.categories(null, null));
        assertSame(horizons, controller.horizons(null, null, null));
        assertSame(reasons, controller.reasons());
        assertSame(suggestions, controller.suggestions());
        verify(currentUser, times(4)).id();
    }

    @Test
    void controllerForwardsOptionalFiltersWithoutInterpretingThem() {
        UUID owner = UUID.randomUUID();
        when(currentUser.id()).thenReturn(owner);
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 13);
        var categories = new PatternResponses.CategoriesResponse(0, 0, List.of());
        var horizons = new PatternResponses.HorizonsResponse(0, List.of());
        when(service.categories(owner, from, to)).thenReturn(categories);
        when(service.horizons(owner, from, to, "rate")).thenReturn(horizons);
        var controller = new PatternController(currentUser, service);
        assertSame(categories, controller.categories(from, to));
        assertSame(horizons, controller.horizons(from, to, "rate"));
        verify(service).categories(owner, from, to);
        verify(service).horizons(owner, from, to, "rate");
    }
}
