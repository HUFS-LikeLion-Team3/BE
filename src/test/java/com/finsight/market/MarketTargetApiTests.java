package com.finsight.market;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.auth.service.TokenService;
import com.finsight.market.entity.MarketTarget;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.util.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:marketTargets;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Transactional
class MarketTargetApiTests {
    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    MockMvc mvc;
    String token;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        token = "Bearer " + tokens.issue(users.save(new User("kakao", UUID.randomUUID().toString(), "tester")));
    }

    void target(String code, String category, String type, boolean active) {
        MarketTarget target = new MarketTarget();
        Map<String, Object> fields = Map.of("code", code, "displayName", "미국 10년물 국채금리",
                "marketCategory", category, "targetType", type,
                "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "큰 방향성 없음"),
                "unit", "%", "calendarCode", "TEST", "timezone", "America/New_York", "dataSymbol", code,
                "active", active);
        fields.forEach((key, value) -> ReflectionTestUtils.setField(target, key, value));
        em.persist(target);
        em.flush();
    }

    @Test void returnsOnlyActiveTargetsWithSpecifiedFields() throws Exception {
        target("US10Y", "rate", "yield", true);
        target("HIDDEN", "rate", "yield", false);
        mvc.perform(get("/api/v1/market-targets").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targets.length()").value(1))
                .andExpect(jsonPath("$.targets[0].id").isString())
                .andExpect(jsonPath("$.targets[0].code").value("US10Y"))
                .andExpect(jsonPath("$.targets[0].displayName").value("미국 10년물 국채금리"))
                .andExpect(jsonPath("$.targets[0].marketCategory").value("rate"))
                .andExpect(jsonPath("$.targets[0].targetType").value("yield"))
                .andExpect(jsonPath("$.targets[0].directionLabels.neutral").value("큰 방향성 없음"))
                .andExpect(jsonPath("$.targets[0].unit").value("%"))
                .andExpect(jsonPath("$.targets[0].calendarCode").doesNotExist());
    }

    @Test void filtersSeparatelyAndTogether() throws Exception {
        target("US10Y", "rate", "yield", true);
        target("SP500", "us_equity", "index", true);
        for (String filter : List.of("marketCategory", "targetType")) {
            mvc.perform(get("/api/v1/market-targets").header("Authorization", token)
                    .param(filter, filter.equals("marketCategory") ? "rate" : "yield"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.targets.length()").value(1))
                    .andExpect(jsonPath("$.targets[0].code").value("US10Y"));
        }
        mvc.perform(get("/api/v1/market-targets").header("Authorization", token)
                .param("marketCategory", "rate").param("targetType", "index"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targets").isEmpty());
    }

    @Test void rejectsInvalidFilters() throws Exception {
        for (String filter : List.of("marketCategory", "targetType")) {
            for (String value : List.of("invalid", "", "RATE")) {
                mvc.perform(get("/api/v1/market-targets").header("Authorization", token).param(filter, value))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("유효하지 않은 시장 대상 조회 조건입니다."))
                        .andExpect(jsonPath("$.path").value("/api/v1/market-targets"))
                        .andExpect(jsonPath("$.timestamp").exists());
            }
        }
    }

    @Test void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/market-targets")).andExpect(status().isUnauthorized());
    }

    @Test void detailIncludesInactiveTargetAndUnavailableReferenceWithoutCreatingBaselines() throws Exception {
        target("US10Y", "rate", "yield", false);
        UUID id = em.createQuery("select t.id from MarketTarget t where t.code = 'US10Y'", UUID.class)
                .getSingleResult();
        long before = em.createQuery("select count(b) from SessionTargetBaseline b", Long.class).getSingleResult();
        mvc.perform(get("/api/v1/market-targets/{id}", id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.isActive").value(false))
                .andExpect(jsonPath("$.referenceMarket.status").value("unavailable"))
                .andExpect(jsonPath("$.referenceMarket.value").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.referenceMarket.asOfAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.referenceMarket.unit").value("%"))
                .andExpect(jsonPath("$.referenceMarket.unavailableReason").isString());
        org.junit.jupiter.api.Assertions.assertEquals(before,
                em.createQuery("select count(b) from SessionTargetBaseline b", Long.class).getSingleResult());
    }

    @Test void detailHandlesMissingIdMalformedIdAndAuthentication() throws Exception {
        mvc.perform(get("/api/v1/market-targets/{id}", UUID.randomUUID()).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("시장 대상을 찾을 수 없습니다."));
        mvc.perform(get("/api/v1/market-targets/not-a-uuid").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/market-targets/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
