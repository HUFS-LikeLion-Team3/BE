package com.finsight.feedback.repository;

import com.finsight.feedback.entity.AiRagJob;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in MySQL integration test. Never runs against the regular finsight DB.
 *
 * Required environment variables:
 *   RUN_ISSUE25_DB_TESTS=true
 *   DB_TEST_PASSWORD=<local MySQL password>
 * Optional:
 *   DB_TEST_USERNAME=<local MySQL username> (defaults to root)
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
        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String requestId = "issue25-jpa-test-" + UUID.randomUUID();
        String requestJson = "{\"input\":{\"stage\":\"initial\",\"news\":{\"briefing\":\""
                + "시장 예측 근거를 원문 그대로 보존합니다. ".repeat(4_000)
                + "\"}}}";

        // > 65 KiB: proves the value requires more than TINYTEXT or TEXT.
        assertTrue(requestJson.getBytes(StandardCharsets.UTF_8).length > 65_535);

        AiRagJob job = AiRagJob.create(
                feedbackId,
                sessionId,
                "initial",
                requestId,
                "sha256:local-test-package",
                requestJson
        );
        repository.saveAndFlush(job);
        entityManager.clear(); // reload from MySQL, not the JPA first-level cache

        AiRagJob reloaded = repository.findById(feedbackId).orElseThrow();
        assertEquals(sessionId, reloaded.getSessionId());
        assertEquals(requestId, reloaded.getBeRequestId());
        assertEquals("frozen", reloaded.getState());
        assertNotNull(reloaded.getRequestJson());
        assertEquals(requestJson, reloaded.getRequestJson());

        // @DataJpaTest rolls back the insertion after the test.
    }
}
