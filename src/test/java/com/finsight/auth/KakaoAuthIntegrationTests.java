package com.finsight.auth;


import com.finsight.auth.repository.*;
import com.finsight.auth.service.*;
import com.finsight.global.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "spring.jpa.show-sql=false", "auth.policy.terms-version=terms-v1", "auth.policy.privacy-version=privacy-v1"
})
class KakaoAuthIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;

    @Autowired KakaoLoginService loginService;
    @Autowired TokenService tokens;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KakaoClient kakao;
    MockMvc mvc;

    @BeforeEach
    void setup() {

        consents.deleteAll();
        users.deleteAll();
        when(kakao.authenticate(anyString())).thenReturn(new KakaoClient.Profile(123L, "사용자"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void publicLoginMatchesResponseContractWithoutCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/kakao").contentType("application/json")
                .content("{\"authorizationCode\":\"valid-code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.displayName").value("사용자"))
                .andExpect(jsonPath("$.user.onboardingCompleted").value(false));
        assertEquals(1, users.count());
        assertEquals(2, consents.count());
        var savedConsents = consents.findAll();
        assertTrue(savedConsents.stream().allMatch(consent -> consent.getConsentedAt() != null));
        assertEquals(java.util.Set.of("terms_of_service", "privacy_policy"), savedConsents.stream()
                .map(consent -> consent.getPolicyType()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(savedConsents.get(0).getConsentedAt(), savedConsents.get(1).getConsentedAt());
        var user = users.findByAuthProviderAndProviderUserId("kakao", "123").orElseThrow();
        assertNull(user.getOnboardingCompletedAt());
        assertNotNull(user.getCreatedAt());
        assertEquals(user.getCreatedAt(), user.getUpdatedAt());
    }

    @Test
    void usersTableMatchesFinalErd() {
        var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'USERS' AND TABLE_SCHEMA = 'PUBLIC'", String.class);
        assertEquals(java.util.Set.of("ID", "AUTH_PROVIDER", "PROVIDER_USER_ID", "DISPLAY_NAME",
                "ONBOARDING_COMPLETED_AT", "CREATED_AT", "UPDATED_AT"), new java.util.HashSet<>(columns));
    }

    @Test
    void providerUserIdIsUniqueAcrossProvidersAndDisplayNameAllowsNull() {
        var user = users.saveAndFlush(new com.finsight.auth.entity.User("kakao", "nullable-name-user", null));
        assertNull(users.findById(user.getId()).orElseThrow().getDisplayName());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                users.saveAndFlush(new com.finsight.auth.entity.User("other", "nullable-name-user", "다른 사용자")));
    }

    @Test
    void consentTimestampColumnMatchesFinalErd() {
        var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'USER_CONSENTS' AND TABLE_SCHEMA = 'PUBLIC'", String.class);
        assertTrue(columns.contains("CONSENTED_AT"));
        assertFalse(columns.contains("AGREED_AT"));
    }

    @Test
    void completedOnboardingIsReportedOnExistingLogin() {
        var first = loginService.login("first-code");
        var user = users.findById(first.user().id()).orElseThrow();
        var createdAt = user.getCreatedAt();
        user.completeOnboarding();
        var completedAt = user.getOnboardingCompletedAt();
        user.completeOnboarding();
        assertEquals(completedAt, user.getOnboardingCompletedAt());
        users.saveAndFlush(user);

        var stored = users.findById(first.user().id()).orElseThrow();
        assertEquals(createdAt, stored.getCreatedAt());
        assertFalse(stored.getUpdatedAt().isBefore(createdAt));
        assertNotNull(stored.getOnboardingCompletedAt());
        var second = loginService.login("second-code");
        assertEquals(first.user().id(), second.user().id());
        assertTrue(second.user().onboardingCompleted());
        assertEquals(2, consents.count());
    }

    @Test
    void existingLoginDoesNotAgreeToNewPolicyVersion() {
        var first = loginService.login("first-code");
        var changedPolicyService = new KakaoLoginService(kakao, users, consents, tokens, manager, "terms-v2", "privacy-v2");
        var second = changedPolicyService.login("second-code");
        assertEquals(first.user().id(), second.user().id());
        assertNotEquals(first.accessToken(), second.accessToken());
        assertEquals(1, users.count());
        assertEquals(2, consents.count());
        assertTrue(consents.findAll().stream().noneMatch(c -> c.getPolicyVersion().endsWith("v2")));
        assertEquals(first.user().id(), tokens.authenticate(first.accessToken()).orElseThrow());
    }

    @Test
    void bearerTokenAuthenticatesAndTamperedTokenReturns401() throws Exception {
        var login = loginService.login("code");
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(login.user().id().toString()));
        String tamperedToken = "x" + login.accessToken().substring(1);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredJwtReturns401AndSchemaHasNoTokenTable() throws Exception {
        var login = loginService.login("code");
        var key = new SecretKeySpec(Base64.getDecoder().decode(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var claims = JwtClaimsSet.builder().issuer("finsight").subject(login.user().id().toString())
                .issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(1)).build();
        String expired = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME IN ('ACCESS_TOKENS', 'REFRESH_TOKENS')", Integer.class));
    }

    @Test
    void missingAndUnknownTokensReturn401() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/learning-sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedAndBlankRequestsReturn400() throws Exception {
        for (String body : new String[]{"{}", "{\"authorizationCode\":\" \"}", "{bad"}) {
            mvc.perform(post("/api/v1/auth/kakao").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(kakao, never()).authenticate(anyString());
    }

    @Test
    void kakaoFailuresMatchErrorContract() throws Exception {
        when(kakao.authenticate("bad-code")).thenThrow(new ApiException(HttpStatus.BAD_REQUEST,
                "유효하지 않은 카카오 인가 코드입니다."));
        mvc.perform(post("/api/v1/auth/kakao").contentType("application/json")
                .content("{\"authorizationCode\":\"bad-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 카카오 인가 코드입니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/kakao"));
        when(kakao.authenticate("failed-code")).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,
                "카카오 인증에 실패했습니다."));
        mvc.perform(post("/api/v1/auth/kakao").contentType("application/json")
                .content("{\"authorizationCode\":\"failed-code\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("카카오 인증에 실패했습니다."));
    }

    @Test
    void missingPolicyVersionsDoNotCreateUserOrConsent() {
        var unconfigured = new KakaoLoginService(kakao, users, consents, tokens, manager, "", "");
        assertThrows(ApiException.class, () -> unconfigured.login("code"));
        assertEquals(0, users.count());
        assertEquals(0, consents.count());

    }
}
