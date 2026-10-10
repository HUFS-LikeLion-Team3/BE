package com.finsight.user.service;

import com.finsight.auth.entity.UserConsent;
import com.finsight.auth.repository.UserConsentRepository;
import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserConsentService {
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final String termsVersion;
    private final String privacyVersion;

    public UserConsentService(UserRepository users, UserConsentRepository consents,
            @Value("${auth.policy.terms-version:}") String termsVersion,
            @Value("${auth.policy.privacy-version:}") String privacyVersion) {
        this.users = users;
        this.consents = consents;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    @Transactional
    public ConsentResponse consent(UUID userId, String policyType, String policyVersion) {
        String registeredVersion = switch (policyType) {
            case "terms_of_service" -> termsVersion;
            case "privacy_policy" -> privacyVersion;
            default -> "";
        };
        if (registeredVersion.isBlank() || !registeredVersion.equals(policyVersion)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 요청입니다.");
        }
        // Serialize consent writes for the same user, including concurrent repeated requests.
        var user = users.findByIdForUpdate(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        var consent = consents.findByUserIdAndPolicyTypeAndPolicyVersion(userId, policyType, policyVersion)
                .orElseGet(() -> consents.saveAndFlush(new UserConsent(user, policyType, policyVersion,
                        Instant.now().truncatedTo(ChronoUnit.MICROS))));
        return ConsentResponse.from(consent);
    }

    @Transactional(readOnly = true)
    public List<ConsentResponse> findAll(UUID userId) {
        if (!users.existsById(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
        }
        return consents.findAllByUserIdOrderByConsentedAtAscIdAsc(userId).stream()
                .map(ConsentResponse::from).toList();
    }

    public record ConsentResponse(UUID id, String policyType, String policyVersion, OffsetDateTime consentedAt) {
        public static ConsentResponse from(UserConsent consent) {
            return new ConsentResponse(consent.getId(), consent.getPolicyType(), consent.getPolicyVersion(),
                    consent.getConsentedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime());
        }
    }
}
