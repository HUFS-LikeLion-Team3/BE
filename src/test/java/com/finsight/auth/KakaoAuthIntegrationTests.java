package com.finsight.auth;

import com.finsight.auth.entity.AccessToken;
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
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false", "auth.policy.terms-version=terms-v1", "auth.policy.privacy-version=privacy-v1"
})
class KakaoAuthIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired AccessTokenRepository accessTokens;
    @Autowired KakaoLoginService loginService;
    @Autowired TokenService tokens;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean KakaoClient kakao;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        accessTokens.deleteAll();
        consents.deleteAll();
        users.deleteAll();
        when(kakao.appId()).thenReturn("test-app");
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
        assertTrue(consents.findAll().stream().allMatch(consent -> consent.getAgreedAt() != null));
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
        assertTrue(accessTokens.findAll().stream().noneMatch(t -> t.getTokenHash().equals(first.accessToken())));
    }

    @Test
    void bearerTokenAuthenticatesAndExpiredTokenReturns401() throws Exception {
        var login = loginService.login("code");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(login.user().id().toString()));
        var user = users.findById(login.user().id()).orElseThrow();
        accessTokens.saveAndFlush(new AccessToken(TokenService.hash(login.accessToken()), user, Instant.now().minusSeconds(1)));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void missingAndUnknownTokensReturn401() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer invalid"))
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
        assertEquals(0, accessTokens.count());
    }
}
