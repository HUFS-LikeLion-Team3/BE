package com.finsight.learning.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record LearningSessionListResponse(
        List<LearningSessionListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static LearningSessionListResponse from(
            Page<LearningSessionListItemResponse> sessions
    ) {
        return new LearningSessionListResponse(
                sessions.getContent(),
                sessions.getNumber(),
                sessions.getSize(),
                sessions.getTotalElements(),
                sessions.getTotalPages(),
                sessions.hasNext()
        );
    }
}
