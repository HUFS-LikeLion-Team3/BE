package com.finsight.outcome.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.outcome.dto.OutcomeResponse;
import com.finsight.outcome.dto.OutcomeSeriesResponse;
import com.finsight.outcome.dto.TargetOutcomeResponse;
import com.finsight.outcome.service.OutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import com.finsight.global.security.CurrentUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class OutcomeController {

    private final CurrentUser currentUser;

    private final OutcomeService outcomeService;

    @GetMapping("/{learningSessionId}/outcomes")
    public ResponseEntity<OutcomeResponse> findAll(
            @PathVariable String learningSessionId
    ) {
        UUID parsedLearningSessionId =
                parseLearningSessionId(learningSessionId);

        // TODO 인증 연동 후 로그인 사용자 ID로 교체
        UUID userId = currentUser.id();

        return ResponseEntity.ok(
                outcomeService.findAll(
                        userId,
                        parsedLearningSessionId
                )
        );
    }

    @GetMapping("/{learningSessionId}/targets/{sessionTargetId}/outcomes")
    public ResponseEntity<TargetOutcomeResponse> findOne(
            @PathVariable String learningSessionId,
            @PathVariable String sessionTargetId
    ) {
        UUID parsedLearningSessionId =
                parseLearningSessionId(learningSessionId);

        UUID parsedSessionTargetId =
                parseSessionTargetId(sessionTargetId);

        // TODO 인증 연동 후 로그인 사용자 ID로 교체
        UUID userId = currentUser.id();

        return ResponseEntity.ok(
                outcomeService.findOne(
                        userId,
                        parsedLearningSessionId,
                        parsedSessionTargetId
                )
        );
    }

    @GetMapping("/{learningSessionId}/targets/{sessionTargetId}/series")
    public ResponseEntity<OutcomeSeriesResponse> findSeries(
            @PathVariable String learningSessionId,
            @PathVariable String sessionTargetId
    ) {
        UUID parsedLearningSessionId =
                parseLearningSessionId(learningSessionId);

        UUID parsedSessionTargetId =
                parseSessionTargetId(sessionTargetId);

        // TODO 인증 연동 후 로그인 사용자 ID로 교체
        UUID userId = currentUser.id();

        return ResponseEntity.ok(
                outcomeService.findSeries(
                        userId,
                        parsedLearningSessionId,
                        parsedSessionTargetId
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

    private UUID parseSessionTargetId(
            String sessionTargetId
    ) {
        try {
            return UUID.fromString(sessionTargetId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "예측 대상 ID 형식이 올바르지 않습니다."
            );
        }
    }
}