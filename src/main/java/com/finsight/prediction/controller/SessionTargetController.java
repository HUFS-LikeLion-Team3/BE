package com.finsight.prediction.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.prediction.dto.SessionTargetResponse;
import com.finsight.prediction.dto.SessionTargetUpdateRequest;
import com.finsight.prediction.service.SessionTargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import com.finsight.global.security.CurrentUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class SessionTargetController {

    private final CurrentUser currentUser;

    private final SessionTargetService sessionTargetService;

    @PutMapping("/{learningSessionId}/targets")
    public ResponseEntity<SessionTargetResponse> update(
            @PathVariable String learningSessionId,
            @RequestBody SessionTargetUpdateRequest request
    ) {
        UUID sessionId = parseLearningSessionId(learningSessionId);

        UUID userId = currentUser.id();

        SessionTargetResponse response =
                sessionTargetService.update(
                        userId,
                        sessionId,
                        request
                );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{learningSessionId}/targets")
    public ResponseEntity<SessionTargetResponse> findAll(
            @PathVariable String learningSessionId
    ) {
        UUID sessionId = parseLearningSessionId(learningSessionId);

        UUID userId = currentUser.id();

        SessionTargetResponse response =
                sessionTargetService.findAll(
                        userId,
                        sessionId
                );

        return ResponseEntity.ok(response);
    }

    private UUID parseLearningSessionId(String learningSessionId) {
        try {
            return UUID.fromString(learningSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "학습 세션 ID 형식이 올바르지 않습니다."
            );
        }
    }
}
