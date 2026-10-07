package com.finsight.feedback.controller;

import com.finsight.feedback.dto.FeedbackListItemResponse;
import com.finsight.feedback.service.FeedbackService;
import com.finsight.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @GetMapping("/api/v1/learning-sessions/{learningSessionId}/feedbacks")
    public ResponseEntity<List<FeedbackListItemResponse>> findAll(
            @PathVariable String learningSessionId
    ) {
        UUID sessionId;

        try {
            sessionId = UUID.fromString(learningSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "학습 세션 ID 형식이 올바르지 않습니다."
            );
        }

        UUID userId = UUID.randomUUID();

        return ResponseEntity.ok(
                feedbackService.findAll(
                        userId,
                        sessionId
                )
        );
    }
}