package com.finsight.user.dto;

import com.finsight.auth.entity.User;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record UserMeResponse(UUID id, String authProvider, String displayName,
        OffsetDateTime onboardingCompletedAt, OffsetDateTime createdAt) {
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    public static UserMeResponse from(User user) {
        return new UserMeResponse(user.getId(), user.getAuthProvider(), user.getDisplayName(),
                user.getOnboardingCompletedAt() == null ? null : user.getOnboardingCompletedAt().atZone(KOREA_ZONE).toOffsetDateTime(),
                user.getCreatedAt().atZone(KOREA_ZONE).toOffsetDateTime());
    }
}
