
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.event.FeedbackSubmissionIntentListener;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.reflection.event.FinalReflectionSubmittedEvent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:issue27_atomicity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "finsight.ai.enabled=true",
        "finsight.ai.rag-polling.enabled=false"
})
@Import({
        FeedbackSubmissionIntentService.class,
        FeedbackSubmissionIntentListener.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FeedbackSubmissionIntentTransactionIntegrationTest {

    @Autowired
    AiFeedbackRepository feedbackRepository;

    @Autowired
    FeedbackSubmissionIntentService intentService;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final OffsetDateTime submittedAt =
            OffsetDateTime.parse("2026-11-05T10:00:00+09:00");

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private AiFeedback find(
            UUID sessionId,
            AiFeedback.FeedbackStage stage
    ) {
        return feedbackRepository
                .findByLearningSessionIdAndFeedbackStageAndVersion(
                        sessionId,
                        stage,
                        1
                )
                .orElseThrow();
    }

    @Test
    void initialIntentIsCommittedAfterSubmissionEvent() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        transaction().executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new PredictionSubmittedEvent(
                            sessionId,
                            userId,
                            submittedAt
                    )
            );
        });

        AiFeedback saved = find(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        assertEquals(
                AiFeedback.FeedbackStatus.pending,
                saved.getStatus()
        );

        assertEquals(
                "feedback:initial:" + sessionId + ":v1",
                saved.getRequestId()
        );

        assertEquals(
                submittedAt.toInstant(),
                saved.getInputCutoffAt().toInstant()
        );

        assertEquals(
                1,
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .size()
        );
    }

    @Test
    void failedTransactionRollsBackIntent() {
        UUID sessionId = UUID.randomUUID();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> transaction().executeWithoutResult(status -> {
                    intentService.recordInitial(
                            sessionId,
                            submittedAt
                    );

                    feedbackRepository.flush();

                    assertTrue(
                            feedbackRepository
                                    .findByLearningSessionIdAndFeedbackStageAndVersion(
                                            sessionId,
                                            AiFeedback.FeedbackStage.INITIAL,
                                            1
                                    )
                                    .isPresent()
                    );

                    throw new IllegalStateException(
                            "forced_submission_rollback"
                    );
                })
        );

        assertEquals(
                "forced_submission_rollback",
                exception.getMessage()
        );

        assertTrue(
                feedbackRepository
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                AiFeedback.FeedbackStage.INITIAL,
                                1
                        )
                        .isEmpty()
        );
    }

    @Test
    void finalIntentPreservesOriginalSubmission() {
        UUID sessionId = UUID.randomUUID();
        UUID originalSubmissionId = UUID.randomUUID();
        UUID conflictingSubmissionId = UUID.randomUUID();

        transaction().executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new FinalReflectionSubmittedEvent(
                            sessionId,
                            originalSubmissionId,
                            submittedAt
                    )
            );
        });

        AiFeedback original = find(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        UUID originalFeedbackId = original.getId();
        String originalRequestId = original.getRequestId();

        assertEquals(
                "feedback:final:"
                        + sessionId + ":" + originalSubmissionId,
                originalRequestId
        );

        transaction().executeWithoutResult(status -> {
            eventPublisher.publishEvent(
                    new FinalReflectionSubmittedEvent(
                            sessionId,
                            conflictingSubmissionId,
                            submittedAt.plusMinutes(5)
                    )
            );
        });

        AiFeedback saved = find(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        assertEquals(
                originalFeedbackId,
                saved.getId()
        );

        assertEquals(
                originalRequestId,
                saved.getRequestId()
        );

        assertEquals(
                submittedAt.toInstant(),
                saved.getInputCutoffAt().toInstant()
        );

        assertEquals(
                1,
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .size()
        );
    }

    @Test
    void intentCannotBeSavedWithoutTransaction() {
        UUID sessionId = UUID.randomUUID();

        assertThrows(
                IllegalTransactionStateException.class,
                () -> intentService.recordInitial(
                        sessionId,
                        submittedAt
                )
        );

        assertTrue(
                feedbackRepository
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                AiFeedback.FeedbackStage.INITIAL,
                                1
                        )
                        .isEmpty()
        );
    }
}
