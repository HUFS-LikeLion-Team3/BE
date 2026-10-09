package com.finsight.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_kakao", columnNames = {"kakao_app_id", "kakao_id"}))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "kakao_app_id", nullable = false, length = 128)
    private String kakaoAppId;
    @Column(name = "kakao_id", nullable = false)
    private Long kakaoId;
    @Column(nullable = false, length = 100)
    private String displayName;
    @Column(nullable = false)
    private boolean onboardingCompleted;
    @Column(nullable = false)
    private Instant createdAt;

    public User(String kakaoAppId, Long kakaoId, String displayName) {
        this.kakaoAppId = kakaoAppId;
        this.kakaoId = kakaoId;
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }
}
