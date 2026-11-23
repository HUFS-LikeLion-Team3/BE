package com.finsight.auth.service;

import com.finsight.auth.entity.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final long ttlSeconds;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public TokenService(@Value("${auth.jwt.secret:}") String secret,
            @Value("${auth.access-token-ttl-seconds:3600}") long ttlSeconds,
            @Value("${auth.jwt.issuer:finsight}") String issuer) {
        this(secret, ttlSeconds, issuer, Clock.systemUTC());
    }

    TokenService(String secret, long ttlSeconds, String issuer, Clock clock) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Access token TTL must be positive");
        if (issuer.isBlank()) throw new IllegalArgumentException("JWT issuer must not be blank");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JWT_SECRET must be a Base64-encoded key");
        }
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 random bytes encoded as Base64");
        }
        var key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestampValidator = new JwtTimestampValidator(Duration.ZERO);
        timestampValidator.setClock(clock);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestampValidator, new JwtIssuerValidator(issuer)));
        this.decoder = jwtDecoder;
        this.ttlSeconds = ttlSeconds;
        this.issuer = issuer;
        this.clock = clock;
    }

    public String issue(User user) {
        return issue(user.getId());
    }

    String issue(UUID userId) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds)).id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public Optional<UUID> authenticate(String token) {
        if (token == null || token.length() > 4096) return Optional.empty();
        try {
            var jwt = decoder.decode(token);
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(clock.instant())
                    || jwt.getSubject() == null) return Optional.empty();
            UUID userId = UUID.fromString(jwt.getSubject());
            if (!userId.toString().equals(jwt.getSubject())) return Optional.empty();
            return Optional.of(userId);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}