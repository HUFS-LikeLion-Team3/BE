package com.finsight.learning.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LearningSessionCreateRequest(
        @NotNull(message = "newsId는 필수입니다.")
        UUID newsId,

        @NotNull(message = "sessionType은 필수입니다.")
        String sessionType
) {
}
