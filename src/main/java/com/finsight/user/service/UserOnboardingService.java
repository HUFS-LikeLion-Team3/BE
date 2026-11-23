package com.finsight.user.service;

import com.finsight.auth.repository.UserConsentRepository;
import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserOnboardingService {
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final String termsVersion;
    private final String privacyVersion;

    public UserOnboardingService(UserRepository users, UserConsentRepository consents,
            @Value("${auth.policy.terms-version:}") String termsVersion,
            @Value("${auth.policy.privacy-version:}") String privacyVersion) {
        this.users = users;
        this.consents = consents;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    @Transactional
    public OnboardingResponse complete(UUID userId) {
        var user = users.findByIdForUpdate(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        if (termsVersion.isBlank() || privacyVersion.isBlank()
                || consents.findByUserIdAndPolicyTypeAndPolicyVersion(userId, "terms_of_service", termsVersion).isEmpty()
                || consents.findByUserIdAndPolicyTypeAndPolicyVersion(userId, "privacy_policy", privacyVersion).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "필수 정책 동의가 필요합니다.");
        }
        user.completeOnboarding();
        users.flush();
        return new OnboardingResponse(user.getOnboardingCompletedAt()
                .atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
    }

    public record OnboardingResponse(OffsetDateTime onboardingCompletedAt) {}
}
