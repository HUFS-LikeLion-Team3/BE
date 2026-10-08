package com.finsight.outcome.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.outcome.dto.OutcomeResponse;
import com.finsight.outcome.service.OutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class OutcomeController {

    private final OutcomeService outcomeService;

    @GetMapping("/{learningSessionId}/outcomes")
    public ResponseEntity<OutcomeResponse> findAll(
            @PathVariable String learningSessionId
    ) {
        UUID sessionId =
                parseLearningSessionId(learningSessionId);

        UUID userId = UUID.randomUUID();

        return ResponseEntity.ok(
                outcomeService.findAll(
                        userId,
                        sessionId
                )
        );
    }

    private UUID parseLearningSessionId(
            String learningSessionId
    ) {
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