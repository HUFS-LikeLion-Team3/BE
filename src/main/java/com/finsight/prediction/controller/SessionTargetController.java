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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class SessionTargetController {

    private final SessionTargetService sessionTargetService;

    @PutMapping("/{sessionId}/targets")
    public ResponseEntity<SessionTargetResponse> update(
            @PathVariable String sessionId,
            @RequestBody SessionTargetUpdateRequest request
    ) {
        UUID learningSessionId = parseSessionId(sessionId);

        // TODO: 인증 기능 구현 후 실제 로그인 userId로 교체
        UUID userId = UUID.randomUUID();

        SessionTargetResponse response =
                sessionTargetService.update(
                        userId,
                        learningSessionId,
                        request
                );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionId}/targets")
    public ResponseEntity<SessionTargetResponse> findAll(
            @PathVariable String sessionId
    ) {
        UUID learningSessionId = parseSessionId(sessionId);

        // TODO: 인증 기능 구현 후 실제 로그인 userId로 교체
        UUID userId = UUID.randomUUID();

        SessionTargetResponse response =
                sessionTargetService.findAll(
                        userId,
                        learningSessionId
                );

        return ResponseEntity.ok(response);
    }

    private UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "학습 세션 ID 형식이 올바르지 않습니다."
            );
        }
    }
}
