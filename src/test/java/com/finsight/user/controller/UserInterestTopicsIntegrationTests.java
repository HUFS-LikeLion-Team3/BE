package com.finsight.user.controller;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.news.entity.News;
import com.finsight.news.repository.UserInterestRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import java.util.*;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:interestTopics;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Transactional
class UserInterestTopicsIntegrationTests {
    private static final String PATH = "/api/v1/users/me/interests";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired UserInterestRepository interests;
    @Autowired TokenService tokens;
    @Autowired EntityManager em;
    private MockMvc mvc;
    private UUID userId;
    private String token;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var user = users.saveAndFlush(new User("kakao", UUID.randomUUID().toString(), "interest tester"));
        userId = user.getId();
        token = "Bearer " + tokens.issue(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate", "exchange_rate", "semiconductor", "energy", "price", "employment"})
    void savesReadsAndRecommendsEveryTopicWithoutFilteringOtherNews(String key) throws Exception {
        var olderMatch = news(key, Instant.parse("2026-10-01T09:00:00Z"));
        var newerOther = news("unmatched_topic", Instant.parse("2026-10-02T09:00:00Z"));
        var body = body(topic(key));
        mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(body));
        // Reload persisted rows rather than relying on the PUT response echo.
        em.clear();
        mvc.perform(get(PATH).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interests.length()").value(1))
                .andExpect(jsonPath("$.interests[0].interestKey").value(key))
                .andExpect(jsonPath("$.interests[0].id").isString());
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "recommended"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(olderMatch.getId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(newerOther.getId().toString()));
        mvc.perform(get("/api/v1/news").header("Authorization", token).param("sort", "latest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(newerOther.getId().toString()));
    }

    @Test void supportsAllTopicsAndMarketsThenRepeatReplaceAndClear() throws Exception {
        var items = new ArrayList<>(List.of("rate", "exchange_rate", "semiconductor", "energy", "price", "employment")
                .stream().map(UserInterestTopicsIntegrationTests::topic).toList());
        items.add("{\"interestType\":\"market\",\"interestKey\":\"korea\"}");
        items.add("{\"interestType\":\"market\",\"interestKey\":\"us\"}");
        var all = body(items.toArray(String[]::new));
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(all))
                    .andExpect(status().isOk()).andExpect(content().json(all));
            assertEquals(8, interests.findByUserId(userId).size());
        }
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.interests[*].interestKey", containsInAnyOrder(
                        "rate", "exchange_rate", "semiconductor", "energy", "price", "employment", "korea", "us")));
        mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(topic("energy"))))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1)).andExpect(jsonPath("$.interests[0].interestKey").value("energy"));
        mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.interests").isEmpty());
        assertTrue(interests.findByUserId(userId).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"interests\":null}", "{\"interests\":[null]}",
        "{\"interests\":[{}]}",
        "{\"interests\":[{\"interestType\":\"unknown\",\"interestKey\":\"energy\"}]}",
        "{\"interests\":[{\"interestType\":\"topic\",\"interestKey\":\"unknown\"}]}",
        "{\"interests\":[{\"interestType\":\"topic\",\"interestKey\":\"Energy\"}]}",
        "{\"interests\":[{\"interestType\":\"market\",\"interestKey\":\"all\"}]}",
        "{\"interests\":[{\"interestType\":\"market\",\"interestKey\":\"energy\"}]}",
        "{\"interests\":[{\"interestType\":\"topic\",\"interestKey\":\"korea\"}]}",
        "{\"interests\":[{\"interestType\":\"topic\",\"interestKey\":\"price\"},{\"interestType\":\"topic\",\"interestKey\":\"price\"}]}"
    })
    void invalidInputPreservesPreviouslySavedInterests(String invalid) throws Exception {
        mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(topic("rate"))))
                .andExpect(status().isOk());
        mvc.perform(put(PATH).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("유효하지 않은 관심사 정보입니다."));
        mvc.perform(get(PATH).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1)).andExpect(jsonPath("$.interests[0].interestKey").value("rate"));
    }

    @Test void savingInterestsRequiresAuthentication() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(topic("exchange_rate"))))
                .andExpect(status().isUnauthorized());
    }

    private News news(String category, Instant publishedAt) {
        var news = new News();
        Map<String, Object> values = Map.of("title", category, "category", category, "briefing", "brief",
                "status", "published", "publishedAt", publishedAt, "referenceAt", publishedAt);
        values.forEach((field, value) -> ReflectionTestUtils.setField(news, field, value));
        em.persist(news);
        em.flush();
        return news;
    }
    private static String topic(String key) { return "{\"interestType\":\"topic\",\"interestKey\":\"" + key + "\"}"; }
    private static String body(String... items) { return "{\"interests\":[" + String.join(",", items) + "]}"; }
}
