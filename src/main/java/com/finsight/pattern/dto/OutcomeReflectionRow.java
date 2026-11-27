package com.finsight.pattern.dto;

import com.finsight.reflection.entity.ReflectionNote;

import java.util.UUID;

/** 최종 회고 원문의 읽기 전용 투영. */
public record OutcomeReflectionRow(
        UUID sessionId,
        UUID sessionTargetId,
        ReflectionNote.PromptKey promptKey,
        String body
) {
}
