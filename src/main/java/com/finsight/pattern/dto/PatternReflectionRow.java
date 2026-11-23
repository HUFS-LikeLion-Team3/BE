package com.finsight.pattern.dto;

import java.util.UUID;

/** 최종 회고의 missed_variable 원문과 해당 뉴스의 읽기 전용 투영. */
public record PatternReflectionRow(UUID newsId, String body) {
}
