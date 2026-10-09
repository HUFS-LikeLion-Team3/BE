package com.finsight.auth.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTests {
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void signedTokenCarriesUserIdAndWorksAcrossServiceInstances() {
        var service = new TokenService(TEST_KEY, 3600, "finsight", CLOCK);
        UUID id = UUID.randomUUID();
        String token = service.issue(id);
        assertEquals(3, token.split("\\.").length);
        assertEquals(id, service.authenticate(token).orElseThrow());
        assertEquals(id, new TokenService(TEST_KEY, 3600, "finsight", CLOCK).authenticate(token).orElseThrow());
        assertNotEquals(token, service.issue(id));
    }

    @Test
    void expiredTokenIsRejectedAtExactExpiry() {
        var issuer = new TokenService(TEST_KEY, 3600, "finsight", CLOCK);
        String token = issuer.issue(UUID.randomUUID());
        assertTrue(new TokenService(TEST_KEY, 3600, "finsight",
                Clock.fixed(NOW.plusSeconds(3599), ZoneOffset.UTC)).authenticate(token).isPresent());
        assertTrue(new TokenService(TEST_KEY, 3600, "finsight",
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC)).authenticate(token).isEmpty());
    }

    @Test
    void wrongKeyWrongIssuerAndMalformedTokensAreRejected() {
        var service = new TokenService(TEST_KEY, 3600, "finsight", CLOCK);
        String token = service.issue(UUID.randomUUID());
        String anotherKey = Base64.getEncoder().encodeToString("different-test-key-with-32-bytes!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(new TokenService(anotherKey, 3600, "finsight", CLOCK).authenticate(token).isEmpty());
        assertTrue(new TokenService(TEST_KEY, 3600, "another-app", CLOCK).authenticate(token).isEmpty());
        assertTrue(service.authenticate("x" + token.substring(1)).isEmpty());
        assertTrue(service.authenticate("not-a-jwt").isEmpty());
        assertTrue(service.authenticate("eyJhbGciOiJub25lIn0.e30.").isEmpty());
    }

    @Test
    void missingExpiryInvalidSubjectAndFutureIssuedAtAreRejected() {
        var service = new TokenService(TEST_KEY, 3600, "finsight", CLOCK);
        assertTrue(service.authenticate(sign(JwtClaimsSet.builder().issuer("finsight")
                .subject(UUID.randomUUID().toString()).issuedAt(NOW).build())).isEmpty());
        assertTrue(service.authenticate(sign(JwtClaimsSet.builder().issuer("finsight")
                .subject("invalid-id").issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build())).isEmpty());
        assertTrue(service.authenticate(sign(JwtClaimsSet.builder().issuer("finsight")
                .subject(UUID.randomUUID().toString()).issuedAt(NOW.plusSeconds(60))
                .expiresAt(NOW.plusSeconds(3600)).build())).isEmpty());
    }

    @Test
    void emptyWeakOrMalformedSigningKeysFailFast() {
        for (String key : new String[]{"", "not-base64!", "c2hvcnQ="}) {
            assertThrows(IllegalArgumentException.class, () -> new TokenService(key, 3600, "finsight", CLOCK));
        }
    }

    private String sign(JwtClaimsSet claims) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(TEST_KEY), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
