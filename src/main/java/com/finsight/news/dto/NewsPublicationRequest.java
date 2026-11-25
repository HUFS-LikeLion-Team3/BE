package com.finsight.news.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NewsPublicationRequest(
        @NotNull UUID requestId,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "rate|exchange_rate|semiconductor|energy|price|employment") String category,
        @NotBlank @Size(max = 8000) String briefing,
        @Size(max = 8000) String backgroundContext,
        @Size(max = 10) List<@NotNull @Valid Fact> facts,
        @NotNull @PastOrPresent Instant publishedAt,
        @PastOrPresent Instant referenceAt,
        @NotNull @Valid Source source,
        @NotNull @Size(min = 1, max = 3) List<@NotNull @Valid Target> targets
) {
    public NewsPublicationRequest {
        if (facts == null) facts = List.of();
    }

    public record Fact(
            @NotBlank @Size(max = 255) String label,
            @NotBlank @Size(max = 255) String valueText,
            @Size(max = 255) String unit,
            @NotNull @PastOrPresent Instant asOfAt
    ) {}

    public record Source(
            @NotBlank @Size(max = 255) String publisher,
            @NotBlank @Size(max = 255) String url,
            @NotNull SourceType type,
            @NotBlank @Size(max = 8000) String excerpt,
            @Size(max = 2000) String publicSummary
    ) {}

    public record Target(@NotNull UUID marketTargetId, @NotBlank @Size(max = 1000) String rationale) {}

    public enum SourceType {
        original_article("original_article"),
        official_statistic("primary_official"),
        government_release("primary_official"),
        central_bank_release("primary_official"),
        research_report("trusted_research");

        private final String tier;

        SourceType(String tier) { this.tier = tier; }

        public String tier() { return tier; }
    }
}
