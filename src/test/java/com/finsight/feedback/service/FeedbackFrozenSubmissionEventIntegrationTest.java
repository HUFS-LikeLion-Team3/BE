
package com.finsight.feedback.service;

import com.finsight.feedback.entity.AiFeedback;
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
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        "spring.datasource.url=jdbc:h2:mem:issue27_event_freeze;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class FeedbackFrozenSubmissionEventIntegrationTest {

    private static final String PACKAGE_ID =
            "sha256:" + "a".repeat(64);

    private static final String INPUT_HASH =
            "b".repeat(64);

    private static final String REQUEST_JSON =
            "{\"frozen\":true,\"stage\":\"initial\"}";

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

    private FeedbackGenerationInput frozenInput(
            OffsetDateTime cutoff
    ) {
        return new FeedbackGenerationInput(
                cutoff,
                Map.of(
                        "inputCutoffAt",
                        cutoff.toString(),
                        "news",
                        Map.of("title", "Frozen market news")
                ),
                Map.of("targets", List.of()),
                Map.of(),
                Map.of(),
                Map.of(),
                INPUT_HASH,
                Set.of(),
                Map.of()
        );
    }

    private void configureSessionLock(UUID sessionId) {
        LearningSession session = mock(LearningSession.class);

        when(sessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));
    }

    private void configureInitial(
            UUID sessionId,
            FeedbackGenerationInput input
    ) {
        when(initialBuilder.buildInitial(
                sessionId,
                SUBMITTED_AT
        )).thenReturn(input);

        when(contractMapper.envelope(
                any(AiFeedback.class),
                same(input),
                eq(PACKAGE_ID),
                isNull()
        )).thenReturn(REQUEST_JSON);
    }

    private void submitInitial(
            UUID sessionId,
            UUID userId
    ) {
        transaction().executeWithoutResult(status -> {
            publisher.publishEvent(
                    new PredictionSubmittedEvent(
                            sessionId,
                            userId,
                            SUBMITTED_AT
                    )
            );
        });
    }

    private AiFeedback feedback(
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
    void initialEventCommitsAllFrozenRecords() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        FeedbackGenerationInput input =
                frozenInput(SUBMITTED_AT);

        configureSessionLock(sessionId);
        configureInitial(sessionId, input);

        submitInitial(sessionId, userId);

        AiFeedback saved = feedback(
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

        var snapshot = snapshotRepository
                .findByAiFeedbackId(saved.getId())
                .orElseThrow();

        assertEquals(
                INPUT_HASH,
                snapshot.getInputHash()
        );

        var job = jobRepository
                .findById(saved.getId())
                .orElseThrow();

        assertEquals(
                PACKAGE_ID,
                job.getPackageId()
        );

        assertEquals(
                REQUEST_JSON,
                job.getRequestJson()
        );

        assertEquals("frozen", job.getState());
        assertNull(job.getJobId());

        verify(initialBuilder, times(1))
                .buildInitial(sessionId, SUBMITTED_AT);

        verify(contractMapper, times(1))
                .envelope(
                        any(AiFeedback.class),
                        same(input),
                        eq(PACKAGE_ID),
                        isNull()
                );
    }

    @Test
    void laterCommitFailureRollsBackAllFrozenRecords() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        configureSessionLock(sessionId);

        configureInitial(
                sessionId,
                frozenInput(SUBMITTED_AT)
        );

        assertThrows(
                IllegalStateException.class,
                () -> transaction().executeWithoutResult(status -> {
                    publisher.publishEvent(
                            new PredictionSubmittedEvent(
                                    sessionId,
                                    userId,
                                    SUBMITTED_AT
                            )
                    );

                    // The freeze listener has HIGHEST_PRECEDENCE.
                    // This callback runs later and forces the
                    // surrounding submission transaction to fail.
                    TransactionSynchronizationManager
                            .registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public int getOrder() {
                                            return Ordered.LOWEST_PRECEDENCE;
                                        }

                                        @Override
                                        public void beforeCommit(
                                                boolean readOnly
                                        ) {
                                            throw new IllegalStateException(
                                                    "forced_commit_failure"
                                            );
                                        }
                                    }
                            );
                })
        );

        // Proves the freeze listener actually executed
        // before the forced commit failure.
        verify(initialBuilder, times(1))
                .buildInitial(sessionId, SUBMITTED_AT);

        assertTrue(
                feedbackRepository
                        .findByLearningSessionIdAndFeedbackStageAndVersion(
                                sessionId,
                                AiFeedback.FeedbackStage.INITIAL,
                                1
                        )
                        .isEmpty()
        );

        assertTrue(
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .isEmpty()
        );
    }

    @Test
    void duplicateInitialEventNeverRebuildsSnapshot() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        configureSessionLock(sessionId);

        FeedbackGenerationInput input =
                frozenInput(SUBMITTED_AT);

        configureInitial(sessionId, input);

        submitInitial(sessionId, userId);

        UUID firstFeedbackId = feedback(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        ).getId();

        // The second event must reuse the frozen records.
        submitInitial(sessionId, userId);

        UUID secondFeedbackId = feedback(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        ).getId();

        assertEquals(firstFeedbackId, secondFeedbackId);

        assertEquals(
                1,
                feedbackRepository
                        .findAllByLearningSessionId(sessionId)
                        .size()
        );

        assertEquals(
                REQUEST_JSON,
                jobRepository
                        .findById(firstFeedbackId)
                        .orElseThrow()
                        .getRequestJson()
        );

        verify(initialBuilder, times(1))
                .buildInitial(sessionId, SUBMITTED_AT);

        verify(contractMapper, times(1))
                .envelope(
                        any(AiFeedback.class),
                        same(input),
                        eq(PACKAGE_ID),
                        isNull()
                );
    }

    @Test
    void finalEventInheritsInitialVersionAndWaitsForJobId() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID reflectionSubmissionId = UUID.randomUUID();

        configureSessionLock(sessionId);

        configureInitial(
                sessionId,
                frozenInput(SUBMITTED_AT)
        );

        submitInitial(sessionId, userId);

        AiFeedback initial = feedback(
                sessionId,
                AiFeedback.FeedbackStage.INITIAL
        );

        OffsetDateTime finalSubmittedAt =
                SUBMITTED_AT.plusDays(3);

        OffsetDateTime outcomeCutoff =
                finalSubmittedAt.plusHours(1);

        FeedbackGenerationInput finalInput =
                frozenInput(outcomeCutoff);

        when(finalBuilder.resolveCutoff(
                sessionId,
                finalSubmittedAt
        )).thenReturn(outcomeCutoff);

        when(finalBuilder.build(
                sessionId,
                outcomeCutoff
        )).thenReturn(finalInput);

        transaction().executeWithoutResult(status -> {
            publisher.publishEvent(
                    new FinalReflectionSubmittedEvent(
                            sessionId,
                            reflectionSubmissionId,
                            finalSubmittedAt
                    )
            );
        });

        AiFeedback savedFinal = feedback(
                sessionId,
                AiFeedback.FeedbackStage.FINAL
        );

        assertEquals(
                initial.getPromptVersion(),
                savedFinal.getPromptVersion()
        );

        assertEquals(
                initial.getPolicyVersion(),
                savedFinal.getPolicyVersion()
        );

        assertEquals(
                "feedback:final:"
                        + sessionId + ":" + reflectionSubmissionId,
                savedFinal.getRequestId()
        );

        assertEquals(
                outcomeCutoff.toInstant(),
                savedFinal.getInputCutoffAt().toInstant()
        );

        var finalSnapshot = snapshotRepository
                .findByAiFeedbackId(savedFinal.getId())
                .orElseThrow();

        assertEquals(
                INPUT_HASH,
                finalSnapshot.getInputHash()
        );

        var finalJob = jobRepository
                .findById(savedFinal.getId())
                .orElseThrow();

        assertEquals(
                PACKAGE_ID,
                finalJob.getPackageId()
        );

        assertEquals(
                "awaiting_initial_job",
                finalJob.getState()
        );

        assertNull(finalJob.getJobId());
        assertNull(finalJob.getRequestJson());

        verify(finalBuilder, times(1))
                .build(sessionId, outcomeCutoff);

        // Initial request only; FINAL cannot be sent
        // before its required initialJobId is known.
        verify(contractMapper, times(1))
                .envelope(
                        any(AiFeedback.class),
                        any(FeedbackGenerationInput.class),
                        eq(PACKAGE_ID),
                        isNull()
                );
    }
}
