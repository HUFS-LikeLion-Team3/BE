package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.service.OutcomeBaselineCoordinator.AttemptStatus;
import com.finsight.outcome.service.OutcomeBaselineCoordinator.BaselineAttempt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutcomeBaselineSchedulerTest {

    private LearningSessionRepository sessionRepository;
    private OutcomeBaselineCoordinator baselineCoordinator;
    private OutcomeCollectionService collectionService;

    private OutcomeBaselineScheduler scheduler;

    private UUID sessionId;
    private UUID sessionTargetId;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(LearningSessionRepository.class);
        baselineCoordinator = mock(OutcomeBaselineCoordinator.class);
        collectionService = mock(OutcomeCollectionService.class);

        scheduler = new OutcomeBaselineScheduler(
                sessionRepository,
                baselineCoordinator,
                collectionService
        );

        sessionId = UUID.randomUUID();
        sessionTargetId = UUID.randomUUID();
    }

    private LearningSession mockSession(
            UUID id,
            LearningSession.SessionType sessionType,
            OffsetDateTime submittedAt
    ) {
        LearningSession session = mock(LearningSession.class);

        when(session.getId()).thenReturn(id);
        when(session.getSessionType()).thenReturn(sessionType);

        if (sessionType == LearningSession.SessionType.live) {
            when(session.getSubmittedAt()).thenReturn(submittedAt);
        }

        return session;
    }

    private LearningSession activeSession(UUID id) {
        return mockSession(
                id,
                LearningSession.SessionType.live,
                OffsetDateTime.parse("2026-10-12T09:00:00+09:00")
        );
    }

    private void mockCandidates(List<LearningSession> sessions) {
        when(sessionRepository.findAllByStatusIn(
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(sessions));
    }

    @Test
    void collectsActiveSubmittedSessions() {
        LearningSession session = activeSession(sessionId);

        mockCandidates(List.of(session));

        BaselineAttempt attempt = new BaselineAttempt(
                sessionTargetId,
                AttemptStatus.READY,
                LocalDate.of(2026, 10, 12)
        );

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of(attempt));

        scheduler.collectBaselines();

        verify(baselineCoordinator).runOnce(sessionId);

        verify(collectionService, never()).expireBaseline(
                any(),
                any(),
                anyString()
        );
    }

    @Test
    void expiresBaselineWhenDeadlinePassed() {
        mockCandidates(List.of(activeSession(sessionId)));

        BaselineAttempt attempt = new BaselineAttempt(
                sessionTargetId,
                AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                LocalDate.of(2026, 10, 9)
        );

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of(attempt));

        scheduler.collectBaselines();

        verify(collectionService).expireBaseline(
                sessionId,
                sessionTargetId,
                "BASELINE_DEADLINE_EXCEEDED"
        );
    }

    @Test
    void doesNotExpirePendingMarketData() {
        mockCandidates(List.of(activeSession(sessionId)));

        BaselineAttempt attempt = new BaselineAttempt(
                sessionTargetId,
                AttemptStatus.RETRY_LATER,
                LocalDate.of(2026, 10, 12)
        );

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of(attempt));

        scheduler.collectBaselines();

        verify(baselineCoordinator).runOnce(sessionId);

        verify(collectionService, never()).expireBaseline(
                any(),
                any(),
                anyString()
        );
    }

    @Test
    void doesNotExpireProviderUnavailable() {
        mockCandidates(List.of(activeSession(sessionId)));

        BaselineAttempt attempt = new BaselineAttempt(
                sessionTargetId,
                AttemptStatus.PROVIDER_UNAVAILABLE,
                null
        );

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of(attempt));

        scheduler.collectBaselines();

        verify(collectionService, never()).expireBaseline(
                any(),
                any(),
                anyString()
        );
    }

    @Test
    void skipsReplayAndUnsubmittedSessions() {
        UUID replayId = UUID.randomUUID();
        UUID draftingId = UUID.randomUUID();

        LearningSession active = activeSession(sessionId);

        LearningSession replay = mockSession(
                replayId,
                LearningSession.SessionType.replay,
                OffsetDateTime.now()
        );

        LearningSession unsubmitted = mockSession(
                draftingId,
                LearningSession.SessionType.live,
                null
        );

        mockCandidates(List.of(
                active,
                replay,
                unsubmitted
        ));

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of());

        scheduler.collectBaselines();

        verify(baselineCoordinator).runOnce(sessionId);

        verify(baselineCoordinator, never()).runOnce(replayId);
        verify(baselineCoordinator, never()).runOnce(draftingId);

        verifyNoInteractions(collectionService);
    }

    @Test
    void failureInOneSessionDoesNotStopOtherSessions() {
        UUID secondSessionId = UUID.randomUUID();

        mockCandidates(List.of(
                activeSession(sessionId),
                activeSession(secondSessionId)
        ));

        when(baselineCoordinator.runOnce(sessionId))
                .thenThrow(new IllegalStateException(
                        "Temporary collection failure"
                ));

        when(baselineCoordinator.runOnce(secondSessionId))
                .thenReturn(List.of());

        scheduler.collectBaselines();

        verify(baselineCoordinator).runOnce(sessionId);
        verify(baselineCoordinator).runOnce(secondSessionId);
    }

    @Test
    void databaseQueryFailureStopsCurrentExecution() {
        when(sessionRepository.findAllByStatusIn(
                anyCollection(),
                any(Pageable.class)
        )).thenThrow(new IllegalStateException(
                "Database temporarily unavailable"
        ));

        assertDoesNotThrow(() -> scheduler.collectBaselines());

        verifyNoInteractions(
                baselineCoordinator,
                collectionService
        );
    }

    @Test
    void emptyCandidatesDoNotTriggerCollection() {
        mockCandidates(List.of());

        scheduler.collectBaselines();

        verifyNoInteractions(
                baselineCoordinator,
                collectionService
        );
    }

    @Test
    void handlesMultipleBaselineResultsInOneSession() {
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();

        mockCandidates(List.of(activeSession(sessionId)));

        when(baselineCoordinator.runOnce(sessionId))
                .thenReturn(List.of(
                        new BaselineAttempt(
                                firstTargetId,
                                AttemptStatus.READY,
                                LocalDate.of(2026, 10, 12)
                        ),
                        new BaselineAttempt(
                                secondTargetId,
                                AttemptStatus.DEADLINE_PASSED_UNRESOLVED,
                                LocalDate.of(2026, 10, 9)
                        )
                ));

        scheduler.collectBaselines();

        verify(collectionService).expireBaseline(
                sessionId,
                secondTargetId,
                "BASELINE_DEADLINE_EXCEEDED"
        );

        verify(collectionService, never()).expireBaseline(
                eq(sessionId),
                eq(firstTargetId),
                anyString()
        );
    }
}