
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.entity.AiRagJob;
import com.finsight.feedback.event.FeedbackFrozenSubmissionEventListener;
import com.finsight.feedback.event.FeedbackSubmissionIntentListener;
import com.finsight.feedback.repository.AiFeedbackInputSnapshotRepository;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.feedback.repository.AiRagJobRepository;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:issue27_failure_isolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "finsight.ai.enabled=true",
        "finsight.ai.freeze-on-submit.enabled=true",
        "finsight.ai.rag-polling.enabled=false",
        "AI_RAG_PACKAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
@Import({
        FeedbackFrozenSubmissionWriter.class,
        FeedbackFrozenSubmissionCoordinator.class,
        FeedbackFrozenSubmissionEventListener.class,
        FeedbackSubmissionIntentService.class,
        FeedbackSubmissionIntentListener.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FeedbackFrozenSubmissionFailureIsolationIntegrationTest {

    private static final String PACKAGE_ID =
            "sha256:" + "a".repeat(64);

    private static final OffsetDateTime SUBMITTED_AT =
            OffsetDateTime.parse(
                    "2026-11-05T10:00:00+09:00"
            );

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    AiFeedbackRepository feedbackRepository;

    @Autowired
    AiFeedbackInputSnapshotRepository snapshotRepository;

    @Autowired
    AiRagJobRepository jobRepository;

    @MockitoBean
    LearningSessionRepository sessionRepository;

    @MockitoBean
    FeedbackGenerationInputBuilder initialBuilder;

    @MockitoBean
    AiRagFinalInputBuilder finalBuilder;

    @MockitoBean
    AiRagContractMapper contractMapper;

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void configureSession(UUID sessionId) {
        when(sessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(
                        mock(LearningSession.class)
                ));
    }

    private AiFeedback findFeedback(
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

    private void assertQuarantined(
            AiFeedback feedback,
            String expectedCode
    ) {
        assertEquals(
                AiFeedback.FeedbackStatus.generating,
                feedback.getStatus()
        );

        AiRagJob job = jobRepository
                .findById(feedback.getId())
                .orElseThrow();

        assertEquals(
                "reconciliation_required",
                job.getState()
        );

        assertEquals(
                expectedCode,
                job.getFailureCode()
        );

        assertNull(job.getJobId());
        assertNull(job.getRequestJson());

        assertTrue(
                snapshotRepository
                        .findByAiFeedbackId(feedback.getId())
                        .isEmpty()
        );
    }

    @Test
    void initialBuilderFailureDoesNotRollBackSubmissionIntent() {

        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        configureSession(sessionId);

        when(initialBuilder.buildInitial(
                sessionId,
                SUBMITTED_AT
        )).thenThrow(
                new IllegalStateException(
                        "test_input_builder_unavailable"
                )
        );

        assertDoesNotThrow(
                () -> transaction().executeWithoutResult(status -> {
                    publisher.publishEvent(
                            new PredictionSubmittedEvent(
                                    sessionId,
                                    userId,
                                    SUBMITTED_AT
                            )
                    );
                })
        );

        AiFeedback feedback = findFeedback(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        assertQuarantined(
                feedback,
                "initial_input_freeze_failed"
        );

        assertEquals(
                "feedback:initial:" + sessionId + ":v1",
                feedback.getRequestId()
        );

        verifyNoInteractions(contractMapper);
    }

    @Test
    void frozenRequestSerializationFailureIsQuarantined() {

        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        configureSession(sessionId);

        FeedbackGenerationInput input =
                new FeedbackGenerationInput(
                        SUBMITTED_AT,
                        Map.of("news", Map.of("title", "Frozen news")),
                        Map.of("targets", List.of()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "b".repeat(64),
                        Set.of(),
                        Map.of()
                );

        when(initialBuilder.buildInitial(
                sessionId,
                SUBMITTED_AT
        )).thenReturn(input);

        when(contractMapper.envelope(
                any(AiFeedback.class),
                same(input),
                eq(PACKAGE_ID),
                isNull()
        )).thenThrow(
                new IllegalStateException(
                        "test_contract_mapper_failure"
                )
        );

        assertDoesNotThrow(
                () -> transaction().executeWithoutResult(status -> {
                    publisher.publishEvent(
                            new PredictionSubmittedEvent(
                                    sessionId,
                                    userId,
                                    SUBMITTED_AT
                            )
                    );
                })
        );

        AiFeedback feedback = findFeedback(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        assertQuarantined(
                feedback,
                "frozen_request_serialization_failed"
        );

        verify(initialBuilder).buildInitial(
                sessionId,
                SUBMITTED_AT
        );

        verify(contractMapper).envelope(
                any(AiFeedback.class),
                same(input),
                eq(PACKAGE_ID),
                isNull()
        );
    }

    @Test
    void missingInitialDoesNotRollBackFinalSubmissionIntent() {

        UUID sessionId = UUID.randomUUID();
        UUID reflectionSubmissionId = UUID.randomUUID();

        configureSession(sessionId);

        assertDoesNotThrow(
                () -> transaction().executeWithoutResult(status -> {
                    publisher.publishEvent(
                            new FinalReflectionSubmittedEvent(
                                    sessionId,
                                    reflectionSubmissionId,
                                    SUBMITTED_AT
                            )
                    );
                })
        );

        AiFeedback feedback = findFeedback(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        assertQuarantined(
                feedback,
                "original_initial_snapshot_missing"
        );

        assertEquals(
                "feedback:final:"
                        + sessionId + ":" + reflectionSubmissionId,
                feedback.getRequestId()
        );

        verifyNoInteractions(
                initialBuilder,
                finalBuilder,
                contractMapper
        );
    }
}
