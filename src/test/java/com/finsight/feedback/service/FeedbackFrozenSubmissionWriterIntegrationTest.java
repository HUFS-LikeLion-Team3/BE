
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:issue27_frozen;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@Import(FeedbackFrozenSubmissionWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FeedbackFrozenSubmissionWriterIntegrationTest {

    @Autowired
    FeedbackFrozenSubmissionWriter writer;

    @Autowired
    AiFeedbackRepository feedbackRepository;

    @Autowired
    AiFeedbackInputSnapshotRepository snapshotRepository;

    @Autowired
    AiRagJobRepository jobRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final OffsetDateTime cutoff =
            OffsetDateTime.parse(
                    "2026-11-05T10:00:00+09:00"
            );

    private final String packageId =
            "sha256:" + "b".repeat(64);

    private final String frozenJson =
            "{\"input\":{\"stage\":\"initial\"}}";

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private FeedbackGenerationInput input(String hash) {
        return new FeedbackGenerationInput(
                cutoff,
                Map.of(
                        "inputCutoffAt", cutoff.toString(),
                        "news", Map.of("title", "Frozen news")
                ),
                Map.of("targets", List.of()),
                Map.of(),
                Map.of(),
                Map.of(),
                hash,
                Set.of(),
                Map.of()
        );
    }

    private UUID persist(
            UUID sessionId,
            String hash
    ) {
        return writer.persist(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL,
                "feedback:initial:" + sessionId + ":v1",
                cutoff,
                "feedback-p0-v1",
                "feedback-policy-p0-v1",
                packageId,
                input(hash),
                frozenJson
        );
    }

    @Test
    void commitsFeedbackSnapshotAndJobTogether() {

        UUID sessionId = UUID.randomUUID();

        UUID feedbackId = transaction().execute(
                status -> persist(
                        sessionId,
                        "a".repeat(64)
                )
        );

        assertNotNull(feedbackId);

        AiFeedback feedback = feedbackRepository
                .findById(feedbackId)
                .orElseThrow();

        assertEquals(
                AiFeedback.FeedbackStatus.pending,
                feedback.getStatus()
        );

        var snapshot = snapshotRepository
                .findByAiFeedbackId(feedbackId)
                .orElseThrow();

        assertEquals(
                "a".repeat(64),
                snapshot.getInputHash()
        );

        var job = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        assertEquals(
                "frozen",
                job.getState()
        );

        assertEquals(
                frozenJson,
                job.getRequestJson()
        );

        assertEquals(
                feedback.getRequestId(),
                job.getBeRequestId()
        );

        assertNull(job.getJobId());
    }

    @Test
    void rollbackRemovesAllThreeRecords() {

        UUID sessionId = UUID.randomUUID();
        UUID[] savedFeedbackId = new UUID[1];

        assertThrows(
                IllegalStateException.class,
                () -> transaction().executeWithoutResult(status -> {

                    savedFeedbackId[0] = persist(
                            sessionId,
                            "a".repeat(64)
                    );

                    feedbackRepository.flush();
                    snapshotRepository.flush();
                    jobRepository.flush();

                    assertTrue(
                            feedbackRepository
                                    .findById(savedFeedbackId[0])
                                    .isPresent()
                    );

                    assertTrue(
                            snapshotRepository
                                    .findByAiFeedbackId(savedFeedbackId[0])
                                    .isPresent()
                    );

                    assertTrue(
                            jobRepository
                                    .findById(savedFeedbackId[0])
                                    .isPresent()
                    );

                    throw new IllegalStateException(
                            "forced_submission_rollback"
                    );
                })
        );

        assertTrue(
                feedbackRepository.findById(
                        savedFeedbackId[0]
                ).isEmpty()
        );

        assertTrue(
                snapshotRepository.findByAiFeedbackId(
                        savedFeedbackId[0]
                ).isEmpty()
        );

        assertTrue(
                jobRepository.findById(
                        savedFeedbackId[0]
                ).isEmpty()
        );
    }

    @Test
    void duplicateSubmissionReusesSameFrozenJob() {

        UUID sessionId = UUID.randomUUID();

        UUID originalId = transaction().execute(
                status -> persist(
                        sessionId,
                        "a".repeat(64)
                )
        );

        UUID repeatedId = transaction().execute(
                status -> persist(
                        sessionId,
                        "a".repeat(64)
                )
        );

        assertEquals(originalId, repeatedId);

        assertEquals(
                1,
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .size()
        );

        assertEquals(
                frozenJson,
                jobRepository
                        .findById(originalId)
                        .orElseThrow()
                        .getRequestJson()
        );
    }

    @Test
    void changedSnapshotHashIsRejected() {

        UUID sessionId = UUID.randomUUID();

        UUID originalId = transaction().execute(
                status -> persist(
                        sessionId,
                        "a".repeat(64)
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> transaction().executeWithoutResult(
                        status -> persist(
                                sessionId,
                                "c".repeat(64)
                        )
                )
        );

        assertEquals(
                "a".repeat(64),
                snapshotRepository
                        .findByAiFeedbackId(originalId)
                        .orElseThrow()
                        .getInputHash()
        );

        assertEquals(
                frozenJson,
                jobRepository
                        .findById(originalId)
                        .orElseThrow()
                        .getRequestJson()
        );
    }

    @Test
    void finalCanFreezeSnapshotBeforeInitialJobIdExists() {

        UUID sessionId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();

        UUID feedbackId = transaction().execute(
                status -> writer.persist(
                        sessionId,
                        AiFeedback.FeedbackStage.FINAL,
                        "feedback:final:"
                                + sessionId + ":" + submissionId,
                        cutoff,
                        "feedback-p0-v1",
                        "feedback-policy-p0-v1",
                        packageId,
                        input("d".repeat(64)),
                        null
                )
        );

        assertNotNull(feedbackId);

        assertTrue(
                snapshotRepository
                        .findByAiFeedbackId(feedbackId)
                        .isPresent()
        );

        var job = jobRepository
                .findById(feedbackId)
                .orElseThrow();

        assertNull(job.getRequestJson());

        assertEquals(
                "awaiting_initial_job",
                job.getState()
        );
    }

    @Test
    void rejectsExecutionWithoutSubmissionTransaction() {

        UUID sessionId = UUID.randomUUID();

        assertThrows(
                IllegalTransactionStateException.class,
                () -> persist(
                        sessionId,
                        "a".repeat(64)
                )
        );

        assertTrue(
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .isEmpty()
        );
    }
}
