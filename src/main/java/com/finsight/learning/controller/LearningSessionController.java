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
import com.finsight.global.security.CurrentUser;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions")
public class LearningSessionController {

    private final CurrentUser currentUser;

    private final LearningSessionService learningSessionService;

    @PostMapping
    public ResponseEntity<LearningSessionCreateResponse> create(
            @Valid @RequestBody LearningSessionCreateRequest request
    ) {
        UUID userId = currentUser.id();

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
            @RequestParam(required = false) String newsId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sessionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = currentUser.id();

        if (page < 0 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "잘못된 조회 조건입니다."
            );
        }

        if (size < 1 || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "잘못된 조회 조건입니다."
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
                        "잘못된 조회 조건입니다."
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
                        "잘못된 조회 조건입니다."
                );
            }
        }

        UUID parsedNewsId = newsId == null ? null
                : parseUuid(newsId, "잘못된 조회 조건입니다.");

        var pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.asc("id")
                )
        );

        var sessions = learningSessionService.findAll(
                userId,
                parsedNewsId,
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
        UUID sessionId = parseUuid(
                learningSessionId, "학습 세션 ID 형식이 올바르지 않습니다.");

        UUID userId = currentUser.id();

        LearningSessionDetailResponse response =
                learningSessionService.findById(
                        userId,
                        sessionId
                );

        return ResponseEntity.ok(response);
    }

    private static UUID parseUuid(String raw, String message) {
        try {
            UUID parsed = UUID.fromString(raw);
            // UUID.fromString accepts some noncanonical shortened forms.
            if (parsed.toString().equalsIgnoreCase(raw)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Return the API-defined validation response below.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
