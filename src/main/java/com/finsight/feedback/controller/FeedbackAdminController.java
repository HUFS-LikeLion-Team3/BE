package com.finsight.feedback.controller;

import com.finsight.feedback.service.FeedbackAdminRetryService;
import com.finsight.global.security.CurrentUser;
import com.finsight.news.service.NewsPublicationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/feedbacks")
@RequiredArgsConstructor
@Validated
public class FeedbackAdminController {
    private final CurrentUser currentUser;
    private final NewsPublicationService admins;
    private final FeedbackAdminRetryService retries;

    /** Retry a failed AI feedback on its original frozen request. Admins only. */
    @PostMapping("/{feedbackId}/retry")
    public FeedbackAdminRetryService.Result retry(@PathVariable UUID feedbackId,
                                                  @RequestBody RetryRequest request) {
        UUID adminId = currentUser.id();
        admins.requireAdmin(adminId);
        return retries.retry(feedbackId, adminId, request == null ? null : request.reason());
    }

    public record RetryRequest(@NotBlank @Size(max = 500) String reason) {}
}
