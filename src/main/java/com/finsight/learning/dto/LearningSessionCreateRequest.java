package com.finsight.learning.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LearningSessionCreateRequest(
        @NotNull
        UUID newsId,

        @NotNull
        String sessionType
) {
}
