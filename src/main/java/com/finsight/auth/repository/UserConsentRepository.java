package com.finsight.auth.repository;
import com.finsight.auth.entity.UserConsent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {
    java.util.List<UserConsent> findAllByUserIdOrderByConsentedAtAscIdAsc(UUID userId);
    java.util.Optional<UserConsent> findByUserIdAndPolicyTypeAndPolicyVersion(
            UUID userId, String policyType, String policyVersion);
}
