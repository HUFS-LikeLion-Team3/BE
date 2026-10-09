package com.finsight.auth.service;

import com.finsight.auth.entity.AccessToken;
import com.finsight.auth.entity.User;
import com.finsight.auth.repository.AccessTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class TokenService {
    private final AccessTokenRepository tokens;
    private final long ttlSeconds;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AccessTokenRepository tokens, @Value("${auth.access-token-ttl-seconds:3600}") long ttlSeconds) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Access token TTL must be positive");
        this.tokens = tokens;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new AccessToken(hash(token), user, Instant.now().plusSeconds(ttlSeconds)));
        return token;
    }

    public Optional<User> authenticate(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return tokens.findByTokenHash(hash(token))
                .filter(saved -> saved.getExpiresAt().isAfter(Instant.now())).map(AccessToken::getUser);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
