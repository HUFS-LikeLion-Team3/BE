package com.finsight.reflection.controller;

import com.finsight.global.exception.ApiException;
import com.finsight.global.security.CurrentUser;
import com.finsight.reflection.dto.ReflectionCreateRequest;
import com.finsight.reflection.dto.ReflectionNoteResponse;
import com.finsight.reflection.service.ReflectionService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/learning-sessions/{learningSessionId}/reflections")
public class ReflectionController {

    private final ReflectionService reflectionService;
    private final CurrentUser currentUser;

    @GetMapping
    public ResponseEntity<List<ReflectionNoteResponse>> findAll(
            @PathVariable String learningSessionId
    ) {
        return ResponseEntity.ok(reflectionService.findAll(
                currentUser.id(), parseSessionId(learningSessionId)));
    }

    @PostMapping
    public ResponseEntity<Object> create(
            @PathVariable String learningSessionId,
            @RequestBody(required = false) ReflectionCreateRequest request
    ) {
        return ResponseEntity.ok(reflectionService.create(
                currentUser.id(), parseSessionId(learningSessionId), request));
    }

    private UUID parseSessionId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");
        }
    }
}
