package com.finsight.reflection.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.reflection.dto.ReflectionCreateRequest;
import com.finsight.reflection.dto.ReflectionNoteResponse;
import com.finsight.reflection.service.ReflectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions/{learningSessionId}/reflections")
public class ReflectionController {

    private final ReflectionService reflectionService;

    @GetMapping
    public ResponseEntity<List<ReflectionNoteResponse>> findAll(
            @PathVariable String learningSessionId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(reflectionService.findAll(
                currentUserId(authentication), parseSessionId(learningSessionId)));
    }

    @PostMapping
    public ResponseEntity<Object> create(
            @PathVariable String learningSessionId,
            @RequestBody(required = false) ReflectionCreateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(reflectionService.create(
                currentUserId(authentication), parseSessionId(learningSessionId), request));
    }

    private UUID parseSessionId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");
        }
    }

    private UUID currentUserId(Authentication authentication) {
        // TODO: Align with the Kakao/JWT principal after the authentication PR is merged.
        // Until then, do NOT generate a random UUID or accept a user-supplied userId.
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
    }
}
