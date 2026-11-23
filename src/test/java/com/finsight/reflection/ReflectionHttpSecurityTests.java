package com.finsight.reflection;

import com.finsight.auth.service.TokenService;
import com.finsight.reflection.service.ReflectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP-level checks that pass through the application's actual Spring Security filter chain. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reflection_http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class ReflectionHttpSecurityTests {

    private static final String PATH = "/api/v1/learning-sessions/"
            + "550e8400-e29b-41d4-a716-446655440001/reflections";

    @Autowired WebApplicationContext context;
    @MockitoBean TokenService tokens;
    @MockitoBean ReflectionService reflections;

    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void getAndPostWithoutTokenReturnSpecified401() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.path").value(PATH));

        mvc.perform(post(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.path").value(PATH));
        verifyNoInteractions(reflections);
    }

    @Test
    void getAndPostWithInvalidBearerTokenReturnSpecified401() throws Exception {
        when(tokens.authenticate("invalid")).thenReturn(Optional.empty());

        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.path").value(PATH));

        mvc.perform(post(PATH).header("Authorization", "Bearer invalid")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.path").value(PATH));
        verify(tokens, times(2)).authenticate("invalid");
        verifyNoInteractions(reflections);
    }

    @Test
    void malformedJsonPreservesExistingInterestAndConsentMessages() throws Exception {
        when(tokens.authenticate("valid-test-token")).thenReturn(Optional.of(UUID.randomUUID()));

        mvc.perform(put("/api/v1/users/me/interests")
                        .header("Authorization", "Bearer valid-test-token")
                        .contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 관심사 정보입니다."));

        mvc.perform(post("/api/v1/users/me/consents")
                        .header("Authorization", "Bearer valid-test-token")
                        .contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));

        mvc.perform(post(PATH).header("Authorization", "Bearer valid-test-token")
                        .contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
        verifyNoInteractions(reflections);
    }
}
