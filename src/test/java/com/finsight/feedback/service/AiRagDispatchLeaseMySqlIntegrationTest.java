package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in #27 real-MySQL lock/lease verification. Never runs as part of normal
 * ./gradlew test. Uses ONLY the dedicated finsight_issue27_test database;
 * Hibernate DDL is disabled and all inserted rows are cleaned up afterwards.
 *
 * Local invocation:
 *   RUN_ISSUE27_DB_TESTS=true ./gradlew test --tests '*AiRagDispatchLeaseMySqlIntegrationTest'
 * Supply DB_TEST_PASSWORD in the local shell without checking it into Git.
 */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/finsight_issue27_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_ISSUE27_DB_TESTS", matches = "true")
@Import(AiRagDispatchLeaseService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiRagDispatchLeaseMySqlIntegrationTest {

    @Autowired private AiRagDispatchLeaseService leaseService;
    @Autowired private AiFeedbackRepository feedbackRepository;
    @Autowired private AiRagJobRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID createdFeedbackId;

    @BeforeEach
    void verifyIsolatedInnoDbSchema() {
        assertEquals("finsight_issue27_test",
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class),
                "Refusing to run against a database other than finsight_issue27_test");
        assertEquals("InnoDB", jdbcTemplate.queryForObject("""
                SELECT ENGINE FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_jobs'
                """, String.class), "MySQL row-level locking requires InnoDB");
        Integer leaseColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_jobs'
                  AND COLUMN_NAME IN ('dispatch_owner', 'dispatch_lease_until')
                """, Integer.class);
        assertEquals(2, leaseColumns, "Dispatch lease columns must exist before testing");
    }

    @AfterEach
    void cleanOnlyThisTestCase() {
        if (createdFeedbackId != null) {
            jobRepository.deleteById(createdFeedbackId);
            feedbackRepository.deleteById(createdFeedbackId);
        }
    }

    @Test
    void simultaneousWorkersOnlyOneAcquiresThePersistedLease() throws Exception {
        UUID feedbackId = createFrozenJob();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> workerA = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return leaseService.tryClaim(feedbackId);
            });
            Future<UUID> workerB = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return leaseService.tryClaim(feedbackId);
            });
            start.countDown();
            UUID ownerA = workerA.get(15, TimeUnit.SECONDS);
            UUID ownerB = workerB.get(15, TimeUnit.SECONDS);

            assertTrue((ownerA == null) != (ownerB == null),
                    "Exactly one MySQL transaction must acquire the dispatch lease");
            UUID winner = ownerA == null ? ownerB : ownerA;
            AiRagJob persistedJob = jobRepository.findById(feedbackId).orElseThrow();
            assertEquals(winner.toString(), persistedJob.getDispatchOwner());
            assertNotNull(persistedJob.getDispatchLeaseUntil());
            assertEquals(AiFeedback.FeedbackStatus.generating,
                    feedbackRepository.findById(feedbackId).orElseThrow().getStatus());

            leaseService.release(feedbackId, winner);
            assertNull(jobRepository.findById(feedbackId).orElseThrow().getDispatchOwner());
        }
    }

    @Test
    void expiredLeaseIsReclaimedWithoutRebuildingTheOriginalInput() {
        UUID feedbackId = createFrozenJob();
        UUID abandonedOwner = UUID.randomUUID();
        AiRagJob job = jobRepository.findById(feedbackId).orElseThrow();
        String frozenJson = job.getRequestJson();
        String requestId = job.getBeRequestId();
        assertTrue(job.claimDispatch(abandonedOwner,
                OffsetDateTime.now().minusMinutes(3), Duration.ofMinutes(1)));
        jobRepository.saveAndFlush(job);

        UUID recoveredOwner = leaseService.tryClaim(feedbackId);
        assertNotNull(recoveredOwner, "An expired lease must be claimable");
        assertNotEquals(abandonedOwner, recoveredOwner);
        AiRagJob recovered = jobRepository.findById(feedbackId).orElseThrow();
        assertEquals(frozenJson, recovered.getRequestJson(), "Frozen input must remain unchanged");
        assertEquals(requestId, recovered.getBeRequestId(), "A recovery must reuse the request ID");
    }

    private UUID createFrozenJob() {
        UUID sessionId = UUID.randomUUID();
        String requestId = "issue27-mysql-" + UUID.randomUUID();
        AiFeedback feedback = AiFeedback.create(
                sessionId, AiFeedback.FeedbackStage.INITIAL, 1, requestId,
                "feedback-p0-v1", "feedback-policy-p0-v1", OffsetDateTime.now());
        feedback = feedbackRepository.saveAndFlush(feedback);
        createdFeedbackId = feedback.getId();
        jobRepository.saveAndFlush(AiRagJob.create(createdFeedbackId, sessionId,
                "initial", requestId, "sha256:" + "a".repeat(64), "{\"frozen\":true}"));
        return createdFeedbackId;
    }
}
