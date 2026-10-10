package com.finsight.news;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.news.entity.*;
import com.finsight.news.repository.UserNewsInterestRepository;
import com.finsight.feedback.entity.FeedbackSourceDocument;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:news;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Transactional
class NewsApiTests {
    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired UserNewsInterestRepository interests;
    MockMvc mvc;
    String token;
    UUID userId;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User user = users.save(new User("kakao", UUID.randomUUID().toString(), "tester"));
        userId = user.getId();
        token = "Bearer " + tokens.issue(user);
    }
    News news(String title, String status, String type, Instant at, Set<String> markets, Set<String> topics) {
        News n = new News();
        Map<String, Object> values = Map.of("title", title, "status", status, "contentType", type,
                "category", "rate", "briefing", "brief", "replayStatus", "not_eligible",
                "publishedAt", at, "markets", new HashSet<>(markets), "topics", new HashSet<>(topics));
        values.forEach((k, v) -> ReflectionTestUtils.setField(n, k, v));
        em.persist(n);
        em.flush();
        return n;
    }
    @Test void recommendationUsesCurrentInterestsBeforePaginationAndKeepsUnmatchedNews() throws Exception {
        Instant at = Instant.parse("2026-10-01T09:00:00Z");
        News newest = news("newest", "published", "live", at.plusSeconds(100), Set.of("KR"), Set.of());
        News match = news("match", "published", "live", at, Set.of("US"), Set.of("rates"));
        news("hidden", "draft", "live", at.plusSeconds(200), Set.of("US"), Set.of("rates"));
        news("replay", "published", "replay", at.plusSeconds(300), Set.of("US"), Set.of());
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.content.length()").value(2));
        interests.saveAndFlush(new UserNewsInterest(userId, Set.of("US"), Set.of("rates")));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(match.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended")
                        .param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
        interests.saveAndFlush(new UserNewsInterest(userId, Set.of("KR"), Set.of()));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended"))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()));
    }
    @Test void tiedResultsAndExplicitFiltersAreStable() throws Exception {
        Instant at = Instant.parse("2026-10-01T09:00:00Z");
        News first = news("a", "published", "live", at, Set.of(), Set.of());
        News second = news("b", "published", "live", at, Set.of(), Set.of());
        News replay = news("replay", "published", "replay", at, Set.of(), Set.of());
        List<String> expected = java.util.stream.Stream.of(first, second)
                .map(n -> n.getId().toString()).sorted().toList();
        for (String sort : List.of("latest", "recommended")) {
            mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", sort)
                            .param("category", "rate").param("replayStatus", "not_eligible"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(expected.get(0)))
                    .andExpect(jsonPath("$.content[1].id").value(expected.get(1)))
                    .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
        }
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("contentType", "replay"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(replay.getId().toString()));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("page", "10"))
                .andExpect(jsonPath("$.content.length()").value(0)).andExpect(jsonPath("$.hasNext").value(false));
    }
    @Test void filtersPaginationAuthenticationAndHiddenDetails() throws Exception {
        for (String path : List.of("/api/v1/news/1", "/api/v1/news/1/sources", "/api/v1/source-documents/1")) {
            mvc.perform(get(path).header("Authorization", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("요청 값의 형식이 올바르지 않습니다."));
        }
        News hidden = news("hidden", "draft", "live", Instant.now(), Set.of(), Set.of());
        for (String path : List.of("/api/v1/news", "/api/v1/news/" + hidden.getId(),
                "/api/v1/news/" + hidden.getId() + "/sources", "/api/v1/source-documents/" + UUID.randomUUID())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
        }
        for (var entry : Map.of("page", "-1", "size", "101", "sort", "bad", "contentType", "bad",
                "category", "bad", "replayStatus", "bad").entrySet()) {
            mvc.perform(get("/api/v1/news").header("Authorization", token).param(entry.getKey(), entry.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("유효하지 않은 뉴스 조회 조건입니다."));
        }
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("page", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/news/" + hidden.getId()).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/news/" + hidden.getId() + "/sources").header("Authorization", token)).andExpect(status().isNotFound());
    }
    @Test void detailAndSourceMetadataDoNotCreateSessions() throws Exception {
        News n = news("published", "published", "live", Instant.parse("2026-10-01T09:00:00Z"), Set.of(), Set.of());
        FeedbackSourceDocument d = new FeedbackSourceDocument();
        UUID documentId = UUID.randomUUID();
        Map<String, Object> values = Map.of("id", documentId, "sourceType", "central_bank_release",
                "selectionTier", "primary_official", "publisher", "Federal Reserve", "title", "Statement",
                "url", "https://example.com", "publishedAt", OffsetDateTime.parse("2026-10-01T09:00:00Z"),
                "primary", true, "contentHash", "sha256-example", "retrievedAt", Instant.parse("2026-10-01T09:10:00Z"));
        values.forEach((k,v) -> ReflectionTestUtils.setField(d, k, v));
        em.persist(d);
        NewsSource s = new NewsSource();
        ReflectionTestUtils.setField(s, "newsId", n.getId());
        ReflectionTestUtils.setField(s, "document", d);
        em.persist(s);
        em.flush();
        mvc.perform(get("/api/v1/news/" + n.getId()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("published"));
        mvc.perform(get("/api/v1/news/" + n.getId() + "/sources").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.sources[0].isPrimary").value(true));
        mvc.perform(get("/api/v1/source-documents/" + documentId).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contentHash").value("sha256-example"))
                .andExpect(jsonPath("$.retrievedAt").value("2026-10-01T09:10:00Z"));
        mvc.perform(get("/api/v1/source-documents/" + UUID.randomUUID()).header("Authorization", token))
                .andExpect(status().isNotFound());
        assertEquals(0L, em.createQuery("select count(s) from LearningSession s", Long.class).getSingleResult());
    }
}
