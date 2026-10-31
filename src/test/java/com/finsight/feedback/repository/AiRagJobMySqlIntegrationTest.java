package com.finsight.feedback.repository;

import com.finsight.auth.entity.User;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.learning.entity.LearningSession;
import com.finsight.news.entity.News;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in real-MySQL JPA test for the isolated finsight_issue25_test DB.
 * Uses persisted parent rows so it also works when FK constraints are enabled.
 *
 * Required environment variables:
 *   RUN_ISSUE25_DB_TESTS=true
 *   DB_TEST_PASSWORD=<local MySQL password>
 * Optional: DB_TEST_USERNAME=<local username> (defaults to root)
 */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/finsight_issue25_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_ISSUE25_DB_TESTS", matches = "true")
class AiRagJobMySqlIntegrationTest {

    @Autowired
    private AiRagJobRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void verifyIsolatedTestDatabase() {
        assertEquals(
                "finsight_issue25_test",
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class),
                "This integration test must never run against the main finsight DB"
        );
        assertEquals(
                "longtext",
                jdbcTemplate.queryForObject("""
                        SELECT DATA_TYPE
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'ai_rag_jobs'
                          AND COLUMN_NAME = 'request_json'
                        """, String.class),
                "ai_rag_jobs.request_json must be LONGTEXT"
        );
    }

    @Test
    void longRequestJsonRoundTripsWithoutTruncation() {
        // Persist the real parent rows before creating ai_rag_jobs.
        // This avoids FK violations if the DB enforces links to feedback/session.
        User user = new User("kakao", UUID.randomUUID().toString(), "issue25-it");
        entityManager.persist(user);

        News news = new News();
        ReflectionTestUtils.setField(news, "title", "issue25 integration test");
        ReflectionTestUtils.setField(news, "category", "economy");
        ReflectionTestUtils.setField(news, "briefing", "Test news for a persisted AI request");
        ReflectionTestUtils.setField(news, "publishedAt", Instant.now());
        ReflectionTestUtils.setField(news, "referenceAt", Instant.now());
        entityManager.persist(news);
        entityManager.flush();

        LearningSession session = LearningSession.create(user.getId(), news.getId());
        entityManager.persist(session);
        entityManager.flush();

        String requestId = "issue25-jpa-test-" + UUID.randomUUID();
        AiFeedback feedback = AiFeedback.create(
                session.getId(),
                AiFeedback.FeedbackStage.INITIAL,
                1,
                requestId,
                "issue25-test-prompt",
                "issue25-test-policy",
                OffsetDateTime.now()
        );
        entityManager.persist(feedback);
        entityManager.flush();

        String requestJson = "{\"input\":{\"stage\":\"initial\",\"news\":{\"briefing\":\""
                + "시장 예측 근거를 원문 그대로 보존합니다. ".repeat(4_000)
                + "\"}}}";

        // > 65 KiB, so even a MySQL TEXT column is insufficient.
        assertTrue(requestJson.getBytes(StandardCharsets.UTF_8).length > 65_535);

        AiRagJob job = AiRagJob.create(
                feedback.getId(),
                session.getId(),
                "initial",
                requestId,
                "sha256:local-test-package",
                requestJson
        );
        repository.saveAndFlush(job);
        entityManager.clear(); // Verify a round trip from MySQL, not the first-level cache.

        AiRagJob reloaded = repository.findById(feedback.getId()).orElseThrow();
        assertEquals(feedback.getId(), reloaded.getFeedbackId());
        assertEquals(session.getId(), reloaded.getSessionId());
        assertEquals(requestId, reloaded.getBeRequestId());
        assertEquals("frozen", reloaded.getState());
        assertNotNull(reloaded.getRequestJson());
        assertEquals(requestJson, reloaded.getRequestJson());

        // @DataJpaTest rolls back all parent rows and ai_rag_jobs after the test.
    }
}
