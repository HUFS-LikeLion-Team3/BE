package com.finsight.news;

import com.finsight.auth.entity.User;
import com.finsight.auth.service.TokenService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.news.repository.NewsRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:news-admin;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "finsight.news-admin.user-ids=11111111-1111-4111-8111-111111111111"
})
class NewsAdminApiTests {
    @Autowired WebApplicationContext context;
    @Autowired TokenService tokens;
    @Autowired MarketTargetRepository markets;
    @Autowired NewsRepository news;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void onlyConfiguredAdminsCanPublish() throws Exception {
        String body = body(UUID.randomUUID(), UUID.randomUUID(), "기사");
        mvc.perform(get("/api/v1/admin/news/access")).andExpect(status().isUnauthorized());
        String token = bearer(UUID.randomUUID());
        mvc.perform(get("/api/v1/admin/news/access").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/news").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void publishesArticleWithTargetAndSourceAndSafelyRetries() throws Exception {
        String token = bearer(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        UUID targetId = market();
        UUID requestId = UUID.randomUUID();
        String body = body(requestId, targetId, "한국 반도체 수출");
        mvc.perform(get("/api/v1/admin/news/access").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));

        long before = news.count();
        String response = mvc.perform(post("/api/v1/admin/news").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newsId = JsonMapper.builder().build().readTree(response).path("id").asString();
        mvc.perform(post("/api/v1/admin/news").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(newsId));
        mvc.perform(post("/api/v1/admin/news").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body(requestId, targetId, "다른 제목")))
                .andExpect(status().isConflict());
        String duplicate = body.replaceFirst(requestId.toString(), UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/admin/news").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict());
        assertEquals(before + 1, news.count());

        String reader = bearer(UUID.randomUUID());
        mvc.perform(get("/api/v1/news/" + newsId + "/target-candidates").header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].marketTargetId").value(targetId.toString()))
                .andExpect(jsonPath("$.candidates[0].rationale").value("국내 주식시장 반응"));
        mvc.perform(get("/api/v1/news/" + newsId + "/sources").header("Authorization", reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].publisher").value("관세청"));
    }

    @Test
    void rejectsUnknownMarketWithoutPublishingArticle() throws Exception {
        long before = news.count();
        mvc.perform(post("/api/v1/admin/news")
                .header("Authorization", bearer(UUID.fromString("11111111-1111-4111-8111-111111111111")))
                .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), UUID.randomUUID(), "잘못된 지표")))
                .andExpect(status().isBadRequest());
        assertEquals(before, news.count());
    }

    private String bearer(UUID userId) {
        User user = new User("kakao", "test-user", "Tester");
        ReflectionTestUtils.setField(user, "id", userId);
        return "Bearer " + tokens.issue(user);
    }

    private UUID market() {
        MarketTarget target = new MarketTarget();
        Map.of("code", "KOSPI-ADMIN-TEST", "displayName", "코스피", "marketCategory", "korea_equity",
                "targetType", "index", "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "보합"),
                "unit", "point", "calendarCode", "XKRX", "timezone", "Asia/Seoul", "dataSymbol", "KS11")
                .forEach((key, value) -> ReflectionTestUtils.setField(target, key, value));
        return markets.saveAndFlush(target).getId();
    }

    private static String body(UUID requestId, UUID marketTargetId, String title) {
        return """
                {
                  "requestId": "%s",
                  "title": "%s",
                  "category": "semiconductor",
                  "briefing": "수출이 증가했습니다.",
                  "publishedAt": "2025-11-20T00:00:00Z",
                  "source": {
                    "type": "official_statistic", "publisher": "관세청", "url": "https://example.com/%s",
                    "excerpt": "테스트용 원문 발췌"
                  },
                  "targets": [{"marketTargetId": "%s", "rationale": "국내 주식시장 반응"}]
                }
                """.formatted(requestId, title, requestId, marketTargetId);
    }
}
