package com.finsight.feedback.controller;

import com.finsight.feedback.dto.FeedbackDetailResponse;
import com.finsight.feedback.dto.FeedbackListItemResponse;
import com.finsight.feedback.service.FeedbackService;
import com.finsight.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import com.finsight.global.security.CurrentUser;

@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final CurrentUser currentUser;

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

        UUID userId = currentUser.id();

        return ResponseEntity.ok(
                feedbackService.findAll(
                        userId,
                        sessionId
                )
        );
    }

    @GetMapping("/api/v1/feedbacks/{feedbackId}")
    public ResponseEntity<FeedbackDetailResponse> findById(
            @PathVariable String feedbackId
    ) {
        UUID parsedFeedbackId;

        try {
            parsedFeedbackId = UUID.fromString(feedbackId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AI 피드백 ID 형식이 올바르지 않습니다."
            );
        }

        UUID userId = currentUser.id();

        return ResponseEntity.ok(
                feedbackService.findById(
                        userId,
                        parsedFeedbackId
                )
        );
    }
}