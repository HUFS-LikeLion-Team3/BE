package com.finsight.news.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.news.dto.NewsPublicationRequest;
import com.finsight.news.dto.NewsResponses.Detail;
import com.finsight.news.entity.News;
import com.finsight.news.service.NewsAdminStatusService;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import com.finsight.news.service.NewsPublicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/news")
@RequiredArgsConstructor
public class NewsAdminController {
    private final CurrentUser currentUser;
    private final NewsPublicationService publication;
    private final NewsAdminStatusService statuses;

    @GetMapping("/access")
    public Access access() {
        publication.requireAdmin(currentUser.id());
        return new Access(true);
    }

    @PostMapping
    public Detail publish(@Valid @RequestBody NewsPublicationRequest request) {
        publication.requireAdmin(currentUser.id());
        return publication.publish(request);
    }

    @GetMapping
    public NewsAdminStatusService.NewsPage list(
            @RequestParam(required = false) News.Status status,
            @RequestParam(defaultValue = "0") int page) {
        publication.requireAdmin(currentUser.id());
        return statuses.list(status, page);
    }

    @PutMapping("/{newsId}/status")
    public NewsAdminStatusService.Item updateStatus(
            @PathVariable UUID newsId, @Valid @RequestBody StatusChange request) {
        publication.requireAdmin(currentUser.id());
        return statuses.change(newsId, request.expectedStatus(), request.status());
    }

    public record StatusChange(@NotNull News.Status expectedStatus, @NotNull News.Status status) {}
    public record Access(boolean allowed) {}
}
