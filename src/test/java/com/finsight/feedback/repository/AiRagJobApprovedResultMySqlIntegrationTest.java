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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #26: opt-in local MySQL round-trip of the immutable approved response.
 *
 * Runs ONLY when RUN_ISSUE26_DB_TESTS=true; targets the local 3307/finsight
 * database, which must already contain ai_rag_jobs. DDL is disabled, and the
 * @DataJpaTest transaction rolls back all inserted test rows.
 *
 * Credentials: DB_TEST_USERNAME (defaults to root), DB_TEST_PASSWORD.
 */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/finsight?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_ISSUE26_DB_TESTS", matches = "true")
class AiRagJobApprovedResultMySqlIntegrationTest {

    @Autowired private AiRagJobRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void verifyExpectedDatabaseAndColumns() {
        assertEquals("finsight",
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class),
                "Refusing to run against a database other than finsight");
        assertEquals("longtext",
                jdbcTemplate.queryForObject("""
                        SELECT DATA_TYPE FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'ai_rag_jobs'
                          AND COLUMN_NAME = 'approved_result_json'
                        """, String.class));
    }

    @Test
    void approvedRawJsonAndHashRoundTripWithoutTruncation() {
        UUID feedbackId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String requestId = "issue26-local-test-" + UUID.randomUUID();

        // Preserve whitespace, UTF-8 and a payload larger than MySQL TEXT.
        String originalJson = " {\n  \"resultHash\": \"sha256:issue26-local\","
                + "\n  \"content\": \"" + "가나다".repeat(10_000)
                + "\", \"excerpt\": \"" + "X".repeat(510) + "TAIL_OK\"\n}";
        assertTrue(originalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_535);

        AiRagJob job = AiRagJob.create(
                feedbackId, sessionId, "initial", requestId,
                "sha256:local-test-package", "{\"input\":true}");
        job.accepted(jobId, "approved");
        job.captureApprovedResult(jobId, originalJson, "sha256:issue26-local");
        OffsetDateTime capturedAt = job.getApprovedResultCapturedAt();

        repository.saveAndFlush(job);
        entityManager.clear();  // Force an actual DB read, not the JPA 1st-level cache.

        AiRagJob loaded = repository.findById(feedbackId).orElseThrow();
        assertEquals(jobId, loaded.getJobId());
        assertEquals(originalJson, loaded.getApprovedResultJson());
        assertEquals("sha256:issue26-local", loaded.getResultHash());
        assertNotNull(loaded.getApprovedResultCapturedAt());
        assertTrue(Math.abs(Duration.between(
                capturedAt.toInstant(), loaded.getApprovedResultCapturedAt().toInstant()
        ).toMillis()) < 1000, "capture time should survive the SQL round trip");

        // The same result is idempotent, but a different raw response is forbidden.
        loaded.captureApprovedResult(jobId, originalJson, "sha256:issue26-local");
        assertThrows(IllegalStateException.class,
                () -> loaded.captureApprovedResult(jobId, "{\"changed\":true}", "sha256:issue26-local"));

        // Spring's @DataJpaTest rolls back the INSERT at the end of this test.
    }
}
