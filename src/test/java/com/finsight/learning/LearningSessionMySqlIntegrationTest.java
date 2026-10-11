package com.finsight.learning;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.news.entity.News;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Opt-in real-MySQL + Spring Security + MVC + JPA integration checks.
 *
 * Only runs when RUN_MYSQL_IT=true. Always points to the dedicated LOCAL
 * `finsight_it` database (NOT the application database). Fixtures roll back.
 * The DB schema may be updated there by Hibernate on test context startup.
 * No external Kakao login or real financial data API is used.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/finsight_it?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=update",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_IT", matches = "true")
@Transactional
class LearningSessionMySqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired LearningSessionRepository sessions;

    private MockMvc mvc;
    private User user;
    private String auth;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = users.saveAndFlush(new User("kakao", UUID.randomUUID().toString(), "learning-it"));
        auth = "Bearer " + tokens.issue(user);
    }

    private News news(String status, String contentType) {
        News news = new News();
        ReflectionTestUtils.setField(news, "title", "학습 세션 통합 테스트");
        ReflectionTestUtils.setField(news, "category", "economy");
        ReflectionTestUtils.setField(news, "briefing", "통합 테스트용 뉴스");
        ReflectionTestUtils.setField(news, "status", status);
        ReflectionTestUtils.setField(news, "contentType", contentType);
        ReflectionTestUtils.setField(news, "replayStatus", "not_eligible");
        ReflectionTestUtils.setField(news, "publishedAt", Instant.now());
        ReflectionTestUtils.setField(news, "referenceAt", Instant.now());
        em.persist(news);
        em.flush();
        return news;
    }

    private String createBody(UUID newsId) {
        return "{\"newsId\":\"" + newsId + "\",\"sessionType\":\"live\"}";
    }

    @Test
    void postOnce201Then200KeepsSameSessionAndSubmittedState() throws Exception {
        News news = news("published", "live");
        String response = mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content(createBody(news.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.newsId").value(news.getId().toString()))
                .andExpect(jsonPath("$.sessionType").value("live"))
                .andExpect(jsonPath("$.status").value("drafting"))
                .andReturn().getResponse().getContentAsString();

        String sessionId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        LearningSession existing = sessions.findById(UUID.fromString(sessionId)).orElseThrow();
        existing.markSubmitted(OffsetDateTime.now());
        em.flush();

        mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content(createBody(news.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.status").value("baseline_pending"));
        assertEquals(UUID.fromString(sessionId), sessions.findByUserIdAndNewsId(user.getId(), news.getId())
                .orElseThrow().getId());
    }

    @Test
    void listAndDetailFilterByOwnerAndValidateParameters() throws Exception {
        News news = news("published", "live");
        String response = mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content(createBody(news.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        mvc.perform(get("/api/v1/learning-sessions")
                        .header("Authorization", auth)
                        .param("newsId", news.getId().toString())
                        .param("status", "drafting").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(sessionId))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/learning-sessions/{id}", sessionId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.savedAt").isEmpty());

        User other = users.saveAndFlush(new User("kakao", UUID.randomUUID().toString(), "other-it"));
        String otherToken = "Bearer " + tokens.issue(other);
        mvc.perform(get("/api/v1/learning-sessions/{id}", sessionId)
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/learning-sessions/{id}", UUID.randomUUID())
                        .header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learning-sessions/{id}", "invalid-uuid")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/learning-sessions")
                        .header("Authorization", auth).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/learning-sessions")
                        .header("Authorization", auth).param("status", "not-a-status"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/learning-sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void draftNewsReplayNewsAndReplaySessionCannotBeCreated() throws Exception {
        News draft = news("draft", "live");
        mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content(createBody(draft.getId())))
                .andExpect(status().isNotFound());
        News replay = news("published", "replay");
        mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content(createBody(replay.getId())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/learning-sessions")
                        .header("Authorization", auth).contentType("application/json")
                        .content("{\"newsId\":\"" + replay.getId() + "\",\"sessionType\":\"replay\"}"))
                .andExpect(status().isBadRequest());
    }
}
