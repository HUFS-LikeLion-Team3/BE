package com.finsight.user.controller;

import com.finsight.auth.entity.User;
import com.finsight.auth.entity.UserConsent;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.repository.UserConsentRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.global.exception.ApiException;
import com.finsight.user.service.UserOnboardingService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:onboarding;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "auth.policy.terms-version=1.0", "auth.policy.privacy-version=1.0"
})
@Transactional
class UserOnboardingTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired TokenService tokens;
    @Autowired UserOnboardingService service;
    @Autowired EntityManager em;
    MockMvc mvc;
    UUID userId;
    String token;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var user = users.saveAndFlush(new User("kakao", UUID.randomUUID().toString(), "tester"));
        userId = user.getId();
        token = "Bearer " + tokens.issue(user);
    }

    void consent(String type, String version) {
        consents.saveAndFlush(new UserConsent(users.getReferenceById(userId), type, version, Instant.now()));
    }

    @Test void completesWithoutInterestsAndKeepsPersistedTimestampOnRepeat() throws Exception {
        consent("terms_of_service", "1.0");
        consent("privacy_policy", "1.0");
        String first = mvc.perform(patch("/api/v1/users/me/onboarding").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompletedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        Instant completed = users.findById(userId).orElseThrow().getOnboardingCompletedAt();
        assertNotNull(completed);
        em.flush();
        em.clear();
        mvc.perform(post("/api/v1/users/me/onboarding").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().json(first));
        assertEquals(completed, users.findById(userId).orElseThrow().getOnboardingCompletedAt());
    }

    @Test void requiresBothCurrentPolicyConsentsWithoutCompleting() throws Exception {
        for (int step = 0; step < 3; step++) {
            if (step == 1) consent("terms_of_service", "1.0");
            if (step == 2) consent("privacy_policy", "old");
            mvc.perform(post("/api/v1/users/me/onboarding").header("Authorization", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("필수 정책 동의가 필요합니다."));
            assertNull(users.findById(userId).orElseThrow().getOnboardingCompletedAt());
        }
    }

    @Test void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/v1/users/me/onboarding"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
        mvc.perform(post("/api/v1/users/me/onboarding").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.path").value("/api/v1/users/me/onboarding"));
    }

    @Test void unsupportedMethodReturns405InsteadOf500() throws Exception {
        mvc.perform(delete("/api/v1/users/me/onboarding").header("Authorization", token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test void missingUserReturnsNotFound() {
        var error = assertThrows(ApiException.class, () -> service.complete(UUID.randomUUID()));
        assertEquals(404, error.getStatus().value());
        assertEquals("사용자를 찾을 수 없습니다.", error.getMessage());
    }

    @Test void controllerUsesCommonNotFoundAndServerErrorResponses() throws Exception {
        var currentUser = org.mockito.Mockito.mock(com.finsight.global.security.CurrentUser.class);
        var mockedService = org.mockito.Mockito.mock(UserOnboardingService.class);
        org.mockito.Mockito.when(currentUser.id()).thenReturn(userId);
        var standalone = MockMvcBuilders.standaloneSetup(new UserOnboardingController(currentUser, mockedService))
                .setControllerAdvice(new com.finsight.global.exception.GlobalExceptionHandler()).build();
        org.mockito.Mockito.when(mockedService.complete(userId)).thenThrow(
                new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        standalone.perform(post("/api/v1/users/me/onboarding"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
        org.mockito.Mockito.doThrow(new IllegalStateException("internal detail")).when(mockedService).complete(userId);
        standalone.perform(post("/api/v1/users/me/onboarding"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/users/me/onboarding"));
    }
}
