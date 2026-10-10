package com.finsight.news.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.news.dto.NewsResponses.*;
import com.finsight.news.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NewsController {
    private final CurrentUser currentUser;
    private final NewsService service;
    @GetMapping("/news")
    public ListResponse list(@RequestParam(required = false) String category,
            @RequestParam(defaultValue = "live") String contentType,
            @RequestParam(required = false) String replayStatus,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return service.list(currentUser.id(), category, contentType, replayStatus, sort, page, size);
    }
    @GetMapping("/news/{newsId}")
    public Detail detail(@PathVariable UUID newsId) { return service.detail(newsId); }
    @GetMapping("/news/{newsId}/sources")
    public Sources sources(@PathVariable UUID newsId) { return service.sources(newsId); }
    @GetMapping("/source-documents/{sourceDocumentId}")
    public Document document(@PathVariable UUID sourceDocumentId) { return service.document(sourceDocumentId); }
}
