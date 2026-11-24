package com.finsight.news.controller;

import com.finsight.global.security.CurrentUser;
import com.finsight.news.dto.NewsPublicationRequest;
import com.finsight.news.dto.NewsResponses.Detail;
import com.finsight.news.service.NewsPublicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/news")
@RequiredArgsConstructor
public class NewsAdminController {
    private final CurrentUser currentUser;
    private final NewsPublicationService publication;

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

    public record Access(boolean allowed) {}
}
