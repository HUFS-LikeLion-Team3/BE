package com.finsight.news;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.news.entity.*;
import com.finsight.news.repository.UserInterestRepository;
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
    @Autowired UserInterestRepository interests;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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
                "publishedAt", at, "referenceAt", at);
        values.forEach((k, v) -> ReflectionTestUtils.setField(n, k, v));
        em.persist(n);
        int order = 1;
        for (String market : markets) {
            MarketTarget target = new MarketTarget();
            Map<String, Object> targetValues = Map.of("code", UUID.randomUUID().toString(),
                    "displayName", market, "marketCategory", market.equals("US") ? "us_equity" : "korea_equity",
                    "targetType", "index", "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "보합"),
                    "unit", "point", "calendarCode", "TEST", "timezone", "Asia/Seoul", "dataSymbol", "TEST");
            targetValues.forEach((k,v) -> ReflectionTestUtils.setField(target, k, v));
            em.persist(target);
            em.persist(new NewsTargetCandidate(n, target, order++));
        }
        em.flush();
        return n;
    }
    @Test void recommendationUsesCurrentInterestsBeforePaginationAndKeepsUnmatchedNews() throws Exception {
        Instant at = Instant.parse("2026-10-01T09:00:00Z");
        News newest = news("newest", "published", "live", at.plusSeconds(100), Set.of("KR"), Set.of());
        News match = news("match", "published", "live", at, Set.of("US"), Set.of("rates"));
        news("hidden", "draft", "live", at.plusSeconds(200), Set.of("US"), Set.of("rates"));
        news("replay", "published", "replay", at.plusSeconds(300), Set.of("US"), Set.of());
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended").param("contentType", "live"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.content.length()").value(2));
        interests.saveAndFlush(new UserInterest(users.getReferenceById(userId), UserInterest.InterestType.market, "us_equity"));
        interests.saveAndFlush(new UserInterest(users.getReferenceById(userId), UserInterest.InterestType.topic, "rate"));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended").param("size", "1").param("contentType", "live"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(match.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended")
                        .param("size", "1").param("page", "1").param("contentType", "live"))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
        interests.deleteByUserId(userId);
        interests.flush();
        interests.saveAndFlush(new UserInterest(users.getReferenceById(userId), UserInterest.InterestType.market, "korea_equity"));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended").param("contentType", "live"))
                .andExpect(jsonPath("$.content[0].id").value(newest.getId().toString()));
    }
    @Test void schemaMatchesErdColumnsWithoutInventedTables() {
        Map<String, Set<String>> expected = Map.of(
            "NEWS", Set.of("ID","CONTENT_TYPE","REPLAY_STATUS","TITLE","CATEGORY","BRIEFING",
                    "PUBLISHED_AT","REFERENCE_AT","CURATED_AT","STATUS","CREATED_AT","UPDATED_AT"),
            "USER_INTERESTS", Set.of("ID","USER_ID","INTEREST_TYPE","INTEREST_KEY","CREATED_AT"),
            "SOURCE_DOCUMENTS", Set.of("ID","NEWS_ID","SOURCE_TYPE","SELECTION_TIER","PUBLISHER",
                    "TITLE","URL","PUBLISHED_AT","IS_PRIMARY","CONTENT_HASH","CONTENT_STORAGE_URI",
                    "RETRIEVED_AT","CREATED_AT","UPDATED_AT"),
            "NEWS_FACTS", Set.of("ID","NEWS_ID","SOURCE_DOCUMENT_ID","LABEL","VALUE_TEXT","UNIT","AS_OF_AT","SORT_ORDER"));
        expected.forEach((table, columns) -> assertEquals(columns, new HashSet<>(jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema='PUBLIC' and table_name=?",
                String.class, table))));
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema='PUBLIC'", String.class));
        for (String old : List.of("NEWS_SOURCES","NEWS_MARKETS","NEWS_TOPICS","USER_NEWS_INTERESTS",
                "USER_INTEREST_MARKETS","USER_INTEREST_TOPICS")) {
            assertFalse(tables.contains(old), old);
        }
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
                            .param("category", "rate").param("replayStatus", "not_eligible").param("contentType", "live"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(expected.get(0)))
                    .andExpect(jsonPath("$.content[1].id").value(expected.get(1)))
                    .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
        }
        mvc.perform(get("/api/v1/news").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("contentType", "replay"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(replay.getId().toString()));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("page", "10"))
                .andExpect(jsonPath("$.content.length()").value(0)).andExpect(jsonPath("$.hasNext").value(false));
    }
    @Test void factsMatchContractAndOnlyExposePublishedNews() throws Exception {
        Instant at = Instant.parse("2026-10-01T09:00:00Z");
        News n = news("facts", "published", "live", at, Set.of(), Set.of());
        News hidden = news("hidden", "draft", "live", at, Set.of(), Set.of());
        News empty = news("empty", "published", "live", at, Set.of(), Set.of());
        NewsFact second = new NewsFact(n.getId(), "변동 폭", "0.00", "%", at, 2);
        NewsFact first = new NewsFact(n.getId(), "기준금리", "4.25", "%", at, 1);
        em.persist(second);
        em.persist(first);
        em.persist(new NewsFact(hidden.getId(), "비공개 수치", "1", "%", at, 1));
        em.flush();
        mvc.perform(get("/api/v1/news/" + n.getId() + "/facts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(2))
                .andExpect(jsonPath("$.facts[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.facts[0].label").value("기준금리"))
                .andExpect(jsonPath("$.facts[0].valueText").value("4.25"))
                .andExpect(jsonPath("$.facts[0].unit").value("%"))
                .andExpect(jsonPath("$.facts[0].asOfAt").value("2026-10-01T09:00:00Z"))
                .andExpect(jsonPath("$.facts[0].sortOrder").value(1))
                .andExpect(jsonPath("$.facts[1].id").value(second.getId().toString()));
        mvc.perform(get("/api/v1/news/" + empty.getId() + "/facts").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.facts").isEmpty());
        for (UUID id : List.of(hidden.getId(), UUID.randomUUID())) {
            mvc.perform(get("/api/v1/news/" + id + "/facts").header("Authorization", token))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("뉴스를 찾을 수 없습니다."));
        }
        mvc.perform(get("/api/v1/news/" + n.getId() + "/facts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/news/1/facts").header("Authorization", token)).andExpect(status().isBadRequest());
    }
    @Test void filtersPaginationAuthenticationAndHiddenDetails() throws Exception {
        mvc.perform(get("/api/v1/news/22222222-2222-4222-8222-222222222222/unknown")
                        .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다."));
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
                "category", " ", "replayStatus", "bad").entrySet()) {
            mvc.perform(get("/api/v1/news").header("Authorization", token).param(entry.getKey(), entry.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("유효하지 않은 뉴스 조회 조건입니다."));
        }
        for (String value : List.of("abc", "1.5", "2147483648")) {
            for (String parameter : List.of("page", "size")) {
                mvc.perform(get("/api/v1/news").header("Authorization", token).param(parameter, value))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("유효하지 않은 뉴스 조회 조건입니다."));
            }
        }
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
        ReflectionTestUtils.setField(d, "news", n);
        em.persist(d);
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
