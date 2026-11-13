package com.finsight.pattern.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.pattern.dto.PatternResponses;
import com.finsight.pattern.service.PatternService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/users/me/patterns")
public class PatternController {
    private final CurrentUser currentUser;
    private final PatternService patternService;

    public PatternController(CurrentUser currentUser, PatternService patternService) {
        this.currentUser = currentUser;
        this.patternService = patternService;
    }

    @GetMapping("/categories")
    public PatternResponses.CategoriesResponse categories(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return patternService.categories(currentUser.id(), from, to);
    }

    @GetMapping("/horizons")
    public PatternResponses.HorizonsResponse horizons(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category) {
        return patternService.horizons(currentUser.id(), from, to, category);
    }

    @GetMapping("/reasons")
    public PatternResponses.ReasonsResponse reasons() {
        return patternService.reasons(currentUser.id());
    }

    @GetMapping("/suggestions")
    public PatternResponses.SuggestionsResponse suggestions() {
        return patternService.suggestions(currentUser.id());
    }
}
