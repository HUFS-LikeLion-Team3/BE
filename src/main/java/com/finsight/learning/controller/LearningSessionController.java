package com.finsight.learning.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.dto.LearningSessionCreateRequest;
import com.finsight.learning.dto.LearningSessionCreateResponse;
import com.finsight.learning.dto.LearningSessionListResponse;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.service.LearningSessionService;
import com.finsight.learning.dto.LearningSessionDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class LearningSessionController {

    private final LearningSessionService learningSessionService;

    @PostMapping
    public ResponseEntity<LearningSessionCreateResponse> create(
            @Valid @RequestBody LearningSessionCreateRequest request
    ) {
        UUID userId = UUID.randomUUID();

        LearningSessionService.SessionResult result =
                learningSessionService.createOrGet(
                        userId,
                        request.newsId(),
                        request.sessionType()
                );

        LearningSessionCreateResponse response =
                LearningSessionCreateResponse.from(result.session());

        if (result.created()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<LearningSessionListResponse> findAll(
            @RequestParam(required = false) UUID newsId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sessionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.randomUUID();

        if (page < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "page는 0 이상이어야 합니다."
            );
        }

        if (size < 1 || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "size는 1 이상 100 이하여야 합니다."
            );
        }

        LearningSession.LearningStatus learningStatus = null;

        if (status != null) {
            try {
                learningStatus =
                        LearningSession.LearningStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "올바르지 않은 status입니다."
                );
            }
        }

        LearningSession.SessionType learningSessionType = null;

        if (sessionType != null) {
            try {
                learningSessionType =
                        LearningSession.SessionType.valueOf(sessionType);
            } catch (IllegalArgumentException e) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "올바르지 않은 sessionType입니다."
                );
            }
        }

        var pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.desc("id")
                )
        );

        var sessions = learningSessionService.findAll(
                userId,
                newsId,
                learningStatus,
                learningSessionType,
                pageable
        );

        return ResponseEntity.ok(
                LearningSessionListResponse.from(sessions)
        );
    }

    @GetMapping("/{learningSessionId}")
    public ResponseEntity<LearningSessionDetailResponse> findById(
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

        LearningSessionDetailResponse response =
                learningSessionService.findById(
                        userId,
                        sessionId
                );

        return ResponseEntity.ok(response);
    }
}
