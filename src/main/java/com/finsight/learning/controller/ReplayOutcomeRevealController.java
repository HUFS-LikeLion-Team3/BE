package com.finsight.learning.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.global.security.CurrentUser;
import com.finsight.learning.service.ReplayOutcomeRevealService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class ReplayOutcomeRevealController {
    private final CurrentUser currentUser;
    private final ReplayOutcomeRevealService revealService;

    public record RevealResponse(UUID sessionId, OffsetDateTime resultsRevealedAt) {}

    /** Call only for the user's explicit '결과 보기' click, never from polling. */
    @PostMapping("/{learningSessionId}/outcomes/reveal")
    public ResponseEntity<RevealResponse> reveal(@PathVariable String learningSessionId) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(learningSessionId);
            if (!sessionId.toString().equalsIgnoreCase(learningSessionId)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "학습 세션 ID 형식이 올바르지 않습니다.");
        }

        OffsetDateTime revealedAt = revealService.reveal(currentUser.id(), sessionId);
        return ResponseEntity.ok(new RevealResponse(sessionId, revealedAt));
    }
}
