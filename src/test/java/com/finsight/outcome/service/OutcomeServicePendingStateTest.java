
package com.finsight.outcome.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.OutcomeCollectionEventRepository;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutcomeServicePendingStateTest {

    @Mock
    LearningSessionRepository learningSessionRepository;

    @Mock
    SessionTargetRepository sessionTargetRepository;

    @Mock
    MarketTargetRepository marketTargetRepository;

    @Mock
    SessionTargetBaselineRepository baselineRepository;

    @Mock
    MarketObservationRepository observationRepository;

    @Mock
    OutcomeSeriesPointRepository seriesPointRepository;

    @Mock
    OutcomeCollectionEventRepository eventRepository;

    private OutcomeService service;

    private UUID userId;
    private UUID sessionId;
    private UUID sessionTargetId;
    private UUID marketTargetId;

    @BeforeEach
    void setUp() {
        service = new OutcomeService(
                learningSessionRepository,
                sessionTargetRepository,
                marketTargetRepository,
                baselineRepository,
                observationRepository,
                seriesPointRepository,
                eventRepository
        );

        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        sessionTargetId = UUID.randomUUID();
        marketTargetId = UUID.randomUUID();
    }

    @Test
    void findAllReturnsPendingWhenBaselineIsNotInitialized() {
        prepareSubmittedSessionWithoutBaseline();

        var response = service.findAll(userId, sessionId);

        assertEquals(sessionId, response.learningSessionId());
        assertEquals(1, response.targets().size());

        var target = response.targets().getFirst();

        assertEquals(sessionTargetId, target.sessionTargetId());
        assertEquals(marketTargetId, target.marketTargetId());

        var baseline = target.baseline();

        assertEquals("pending", baseline.status());
        assertEquals("USD", baseline.unit());
        assertNull(baseline.baselineSessionDate());
        assertNull(baseline.baselineAt());
        assertNull(baseline.baselineValue());
        assertNull(baseline.expectedBaselineDate());
        assertNull(baseline.baselineDeadlineDate());
        assertNull(baseline.unavailableReason());

        assertEquals(3, target.observations().size());

        assertEquals(
                List.of(1, 5, 20),
                target.observations()
                        .stream()
                        .map(o -> o.horizon())
                        .toList()
        );

        for (var observation : target.observations()) {
            assertEquals("pending", observation.status());
            assertEquals("none", observation.exceptionStatus());
            assertEquals("USD", observation.unit());
            assertNull(observation.expectedObservationDate());
            assertNull(observation.observationDeadlineDate());
            assertNull(observation.observationSessionDate());
            assertNull(observation.observedAt());
            assertNull(observation.observedValue());
            assertNull(observation.changeValue());
            assertNull(observation.changePercent());
            assertEquals(0, observation.observationDelaySessions());
            assertFalse(observation.usableForAiEvidence());
        }

        verify(baselineRepository)
                .findBySessionTargetId(sessionTargetId);

        verifyNoInteractions(
                seriesPointRepository,
                eventRepository
        );

        verify(baselineRepository, never()).save(any());
        verify(observationRepository, never()).save(any());
    }

    @Test
    void findOneReturnsPendingWhenBaselineIsNotInitialized() {
        prepareSubmittedSessionWithoutBaseline();

        var response = service.findOne(
                userId,
                sessionId,
                sessionTargetId
        );

        assertEquals(sessionId, response.learningSessionId());
        assertEquals(sessionTargetId, response.sessionTargetId());
        assertEquals(marketTargetId, response.marketTargetId());

        assertEquals("pending", response.baseline().status());
        assertNull(response.baseline().baselineValue());
        assertNull(response.baseline().expectedBaselineDate());

        assertEquals(3, response.observations().size());

        for (var observation : response.observations()) {
            assertEquals("pending", observation.status());
            assertNull(observation.observedValue());
            assertFalse(observation.usableForAiEvidence());
        }

        verify(baselineRepository, never()).save(any());
        verify(observationRepository, never()).save(any());
    }

    @Test
    void findAllRejectsUnsubmittedSession() {
        prepareUnsubmittedSession();

        assertThrows(
                ApiException.class,
                () -> service.findAll(userId, sessionId)
        );

        verifyNoInteractions(
                sessionTargetRepository,
                baselineRepository,
                observationRepository
        );
    }

    @Test
    void findOneRejectsUnsubmittedSession() {
        prepareUnsubmittedSession();

        assertThrows(
                ApiException.class,
                () -> service.findOne(
                        userId,
                        sessionId,
                        sessionTargetId
                )
        );

        verifyNoInteractions(
                sessionTargetRepository,
                baselineRepository,
                observationRepository
        );
    }

    @Test
    void findSeriesRejectsUnsubmittedSession() {
        prepareUnsubmittedSession();

        assertThrows(
                ApiException.class,
                () -> service.findSeries(
                        userId,
                        sessionId,
                        sessionTargetId
                )
        );

        verifyNoInteractions(seriesPointRepository);
    }

    private void prepareUnsubmittedSession() {
        LearningSession session = LearningSession.create(
                userId,
                UUID.randomUUID()
        );

        when(learningSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));
    }

    private void prepareSubmittedSessionWithoutBaseline() {
        LearningSession session = LearningSession.create(
                userId,
                UUID.randomUUID()
        );

        session.markSubmitted(OffsetDateTime.now());

        when(learningSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(session));

        SessionTarget target = SessionTarget.create(
                sessionId,
                marketTargetId,
                1
        );

        ReflectionTestUtils.setField(
                target,
                "id",
                sessionTargetId
        );

        when(sessionTargetRepository
                .findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        when(baselineRepository
                .findBySessionTargetId(sessionTargetId))
                .thenReturn(Optional.empty());

        when(observationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(
                        sessionTargetId
                ))
                .thenReturn(List.of());

        MarketTarget marketTarget = mock(MarketTarget.class);

        when(marketTarget.getUnit()).thenReturn("USD");

        when(marketTargetRepository.findById(marketTargetId))
                .thenReturn(Optional.of(marketTarget));
    }
}
