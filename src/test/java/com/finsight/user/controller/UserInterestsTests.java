package com.finsight.user.controller;

import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.GlobalExceptionHandler;
import com.finsight.global.security.CurrentUser;
import com.finsight.news.entity.UserInterest;
import com.finsight.news.repository.UserInterestRepository;
import com.finsight.user.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserInterestsTests {
    private final UUID userId = UUID.randomUUID();
    private final UserInterestRepository interests = mock(UserInterestRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserController(currentUser,
            new UserService(mock(UserRepository.class), interests)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void returnsOnlyStoredInterestsAndNormalizesMarketKeys() throws Exception {
        when(currentUser.id()).thenReturn(userId);
        var korea = interest(UserInterest.InterestType.market, "korea_equity");
        var us = interest(UserInterest.InterestType.market, "us_equity");
        var topic = interest(UserInterest.InterestType.topic, "semiconductor");
        when(interests.findByUserId(userId)).thenReturn(List.of(korea, us, topic));
        mvc.perform(get("/api/v1/users/me/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(3))
                .andExpect(jsonPath("$.interests[0].id").value(korea.getId().toString()))
                .andExpect(jsonPath("$.interests[0].interestType").value("market"))
                .andExpect(jsonPath("$.interests[0].interestKey").value("korea"))
                .andExpect(jsonPath("$.interests[1].interestKey").value("us"))
                .andExpect(jsonPath("$.interests[2].interestType").value("topic"))
                .andExpect(jsonPath("$.interests[2].interestKey").value("semiconductor"));
        verify(interests).findByUserId(userId);
    }

    @Test void returnsEmptyArrayWithoutVirtualDefaults() throws Exception {
        when(currentUser.id()).thenReturn(userId);
        when(interests.findByUserId(userId)).thenReturn(List.of());
        mvc.perform(get("/api/v1/users/me/interests"))
                .andExpect(status().isOk()).andExpect(content().json("{\"interests\":[]}"));
    }

    @Test void requiresLogin() throws Exception {
        var anonymous = MockMvcBuilders.standaloneSetup(new UserController(new CurrentUser(),
                mock(UserService.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        anonymous.perform(get("/api/v1/users/me/interests"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/api/v1/users/me/interests"));
    }

    @Test void hidesInternalFailureDetails() throws Exception {
        when(currentUser.id()).thenReturn(userId);
        when(interests.findByUserId(userId)).thenThrow(new IllegalStateException("internal detail"));
        mvc.perform(get("/api/v1/users/me/interests"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    private UserInterest interest(UserInterest.InterestType type, String key) {
        var interest = mock(UserInterest.class);
        when(interest.getId()).thenReturn(UUID.randomUUID());
        when(interest.getInterestType()).thenReturn(type);
        when(interest.getInterestKey()).thenReturn(key);
        return interest;
    }
}
