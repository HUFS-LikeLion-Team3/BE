package com.finsight.prediction.security;

import com.finsight.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Assumes authentication.getName() is the internal users.id (UUID).
 * Adapt this class when the Kakao/JWT security principal contract is merged.
 * Never manufacture a random user ID to bypass authorization.
 */
@Component
public class CurrentUserIdResolver {
    public UUID requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (authentication.getPrincipal() instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인 사용자 정보를 확인할 수 없습니다.");
        }
    }
}
