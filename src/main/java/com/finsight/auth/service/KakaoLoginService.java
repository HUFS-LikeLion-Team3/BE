package com.finsight.auth.service;

import com.finsight.auth.entity.User;
import com.finsight.auth.entity.UserConsent;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.repository.UserConsentRepository;
import com.finsight.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KakaoLoginService {
    private static final String AUTH_PROVIDER = "kakao";
    private final KakaoClient kakao;
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final TokenService tokens;
    private final TransactionTemplate transaction;
    private final String termsVersion;
    private final String privacyVersion;

    public KakaoLoginService(KakaoClient kakao, UserRepository users, UserConsentRepository consents,
            TokenService tokens, PlatformTransactionManager manager,
            @Value("${auth.policy.terms-version:}") String termsVersion,
            @Value("${auth.policy.privacy-version:}") String privacyVersion) {
        this.kakao = kakao;
        this.users = users;
        this.consents = consents;
        this.tokens = tokens;
        this.transaction = new TransactionTemplate(manager);
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    public LoginResponse login(String code) {
        var profile = kakao.authenticate(code);
        try {
            return transaction.execute(status -> loginOrRegister(profile, true));
        } catch (DataIntegrityViolationException concurrentSignup) {
            // A concurrent first login may have created the same Kakao account.
            return transaction.execute(status -> loginOrRegister(profile, false));
        }
    }

    private LoginResponse loginOrRegister(KakaoClient.Profile profile, boolean allowSignup) {
        String providerUserId = profile.id().toString();
        User user = users.findByAuthProviderAndProviderUserId(AUTH_PROVIDER, providerUserId).orElseGet(() -> {
            if (!allowSignup) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
            if (termsVersion.isBlank() || privacyVersion.isBlank()) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "현재 정책 버전 설정이 필요합니다.");
            }
            User created = users.saveAndFlush(new User(AUTH_PROVIDER, providerUserId, profile.displayName()));
            Instant consentedAt = Instant.now();
            consents.saveAll(List.of(new UserConsent(created, "terms_of_service", termsVersion, consentedAt),
                    new UserConsent(created, "privacy_policy", privacyVersion, consentedAt)));
            return created;
        });
        return new LoginResponse(tokens.issue(user), UserResponse.from(user));
    }

    public record LoginResponse(String accessToken, UserResponse user) {}
    public record UserResponse(UUID id, String displayName, boolean onboardingCompleted) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getDisplayName(), user.getOnboardingCompletedAt() != null);
        }
    }
}
