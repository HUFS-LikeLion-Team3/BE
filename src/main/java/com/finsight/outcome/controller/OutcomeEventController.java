package com.finsight.outcome.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.outcome.dto.OutcomeEventResponse;
import com.finsight.outcome.service.OutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/session-targets")
public class OutcomeEventController {

    private final OutcomeService outcomeService;

    @GetMapping("/{sessionTargetId}/outcome-events")
    public ResponseEntity<List<OutcomeEventResponse>> findEvents(
            @PathVariable String sessionTargetId
    ) {
        UUID parsedSessionTargetId =
                parseSessionTargetId(sessionTargetId);

        // TODO 인증 연동 후 로그인 사용자 ID로 교체
        UUID userId = UUID.randomUUID();

        return ResponseEntity.ok(
                outcomeService.findEvents(
                        userId,
                        parsedSessionTargetId
                )
        );
    }

    private UUID parseSessionTargetId(
            String sessionTargetId
    ) {
        try {
            return UUID.fromString(sessionTargetId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "세션 대상 ID 형식이 올바르지 않습니다."
            );
        }
    }
}