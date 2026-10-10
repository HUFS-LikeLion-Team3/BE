package com.finsight.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(
        name = "uk_users_provider_user_id", columnNames = {"provider_user_id"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "auth_provider", nullable = false, length = 30)
    private String authProvider;
    @Column(name = "provider_user_id", nullable = false, length = 128)
    private String providerUserId;
    @Column(name = "display_name", length = 100)
    private String displayName;
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String authProvider, String providerUserId, String displayName) {
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.displayName = displayName;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public void completeOnboarding() {
        if (onboardingCompletedAt == null) {
            onboardingCompletedAt = Instant.now();
        }
    }
}
