package com.finsight.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_consents", uniqueConstraints = @UniqueConstraint(name = "uk_user_policy_version", columnNames = {"user_id", "policy_type", "policy_version"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class UserConsent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "policy_type", nullable = false, length = 30)
    private String policyType;
    @Column(name = "policy_version", nullable = false, length = 100)
    private String policyVersion;
    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    public UserConsent(User user, String policyType, String policyVersion, Instant consentedAt) {
        this.user = user;
        this.policyType = policyType;
        this.policyVersion = policyVersion;
        this.consentedAt = consentedAt;
    }
}
