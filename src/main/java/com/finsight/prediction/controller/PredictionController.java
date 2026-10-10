package com.finsight.prediction.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.prediction.dto.*;
import com.finsight.prediction.security.CurrentUserIdResolver;
import com.finsight.prediction.service.PredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions/{learningSessionId}")
public class PredictionController {
    private final PredictionService predictionService;
    private final CurrentUserIdResolver currentUserIdResolver;

    @PutMapping("/targets/{sessionTargetId}/predictions")
    public ResponseEntity<PredictionUpdateResponse> update(
            @PathVariable String learningSessionId,
            @PathVariable String sessionTargetId,
            @RequestBody PredictionUpdateRequest request,
            Authentication authentication
    ) {
        UUID sessionId = parseUuid(learningSessionId);
        UUID targetId = parseUuid(sessionTargetId);
        UUID userId = currentUserIdResolver.requireUserId(authentication);
        return ResponseEntity.ok(predictionService.update(userId, sessionId, targetId, request));
    }

    @GetMapping("/targets/{sessionTargetId}/predictions")
    public ResponseEntity<PredictionResponse> findByTarget(
            @PathVariable String learningSessionId,
            @PathVariable String sessionTargetId,
            Authentication authentication
    ) {
        UUID sessionId = parseUuid(learningSessionId);
        UUID targetId = parseUuid(sessionTargetId);
        UUID userId = currentUserIdResolver.requireUserId(authentication);
        return ResponseEntity.ok(predictionService.findByTarget(userId, sessionId, targetId));
    }

    @PostMapping("/submit")
    public ResponseEntity<PredictionSubmitResponse> submit(
            @PathVariable String learningSessionId,
            Authentication authentication
    ) {
        UUID sessionId = parseUuid(learningSessionId);
        UUID userId = currentUserIdResolver.requireUserId(authentication);
        return ResponseEntity.ok(predictionService.submit(userId, sessionId));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "요청한 ID 형식이 올바르지 않습니다.");
        }
    }
}
