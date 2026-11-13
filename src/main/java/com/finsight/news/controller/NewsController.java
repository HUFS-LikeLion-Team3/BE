
package com.finsight.news.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.news.dto.NewsResponses.*;
import com.finsight.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NewsController {

    private final CurrentUser currentUser;
    private final NewsService service;

    @GetMapping("/news")
    public ListResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String replayStatus,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) UUID marketTargetId
    ) {
        return service.list(
                currentUser.id(),
                category,
                market,
                contentType,
                replayStatus,
                sort,
                page,
                size,
                from,
                to,
                marketTargetId
        );
    }

    public ListResponse list(
            String category,
            String market,
            String contentType,
            String replayStatus,
            String sort,
            int page,
            int size
    ) {
        return service.list(
                currentUser.id(),
                category,
                market,
                contentType,
                replayStatus,
                sort,
                page,
                size
        );
    }

    @GetMapping("/news/{newsId}")
    public Detail detail(@PathVariable UUID newsId) {
        return service.detail(newsId);
    }

    @GetMapping("/news/{newsId}/target-candidates")
    public Candidates targetCandidates(@PathVariable UUID newsId) {
        return service.targetCandidates(newsId);
    }

    @GetMapping("/news/{newsId}/facts")
    public Facts facts(@PathVariable UUID newsId) {
        return service.facts(newsId);
    }

    @GetMapping("/news/{newsId}/sources")
    public Sources sources(@PathVariable UUID newsId) {
        return service.sources(newsId);
    }

    @GetMapping("/source-documents/{sourceDocumentId}")
    public Document document(@PathVariable UUID sourceDocumentId) {
        return service.document(sourceDocumentId);
    }
}
