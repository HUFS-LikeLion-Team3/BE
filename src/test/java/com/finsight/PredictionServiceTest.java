
package com.finsight;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.news.entity.News;
import com.finsight.prediction.dto.PredictionResponse;
import com.finsight.prediction.dto.PredictionUpdateRequest;
import com.finsight.prediction.entity.Prediction;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.prediction.repository.PredictionRepository;
import com.finsight.prediction.repository.SessionTargetRepository;
import com.finsight.prediction.service.PredictionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PredictionServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final Instant articleReferenceAt =
            Instant.parse("2020-09-10T04:30:00Z");

    private LearningSession session;
    private SessionTarget target;
    private LearningSessionRepository sessionRepo;
    private SessionTargetRepository targetRepo;
    private PredictionRepository predictionRepo;
    private ApplicationEventPublisher publisher;
    private PredictionService service;
    private EntityManager entityManager;
    private TypedQuery<Long> candidateQuery;
    private News news;

    @BeforeEach
    void setUp() throws Exception {
        session = LearningSession.create(userId, UUID.randomUUID());
        target = SessionTarget.create(sessionId, UUID.randomUUID(), 1);

        Field id = SessionTarget.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(target, targetId);

        sessionRepo = mock(LearningSessionRepository.class);
        targetRepo = mock(SessionTargetRepository.class);
        predictionRepo = mock(PredictionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        entityManager = mock(EntityManager.class);
        candidateQuery = mock(TypedQuery.class);

        news = mock(News.class);
        when(news.getReferenceAt()).thenReturn(articleReferenceAt);
        when(entityManager.find(News.class, session.getNewsId()))
                .thenReturn(news);

        service = new PredictionService(
                sessionRepo,
                targetRepo,
                predictionRepo,
                publisher,
                entityManager,
                mock(com.finsight.learning.service.LearningSessionStatusService.class)
        );

        when(entityManager.createQuery(anyString(), eq(Long.class)))
                .thenReturn(candidateQuery);

        when(candidateQuery.setParameter(anyString(), any()))
                .thenReturn(candidateQuery);

        when(candidateQuery.getSingleResult()).thenReturn(1L);

        when(sessionRepo.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        when(sessionRepo.findById(sessionId))
                .thenReturn(Optional.of(session));

        when(targetRepo.findById(targetId))
                .thenReturn(Optional.of(target));
    }

    @Test
    void partialUpdatePreservesOmittedDirectionAndClearsExplicitNullReason() {
        Prediction original = Prediction.create(targetId, 1);
        original.update(true, Prediction.Direction.up, true, "이유 보존?");

        when(predictionRepo.findBySessionTargetIdAndHorizon(targetId, (short) 1))
                .thenReturn(Optional.of(original));

        when(predictionRepo.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(original));

        var response = service.update(
                userId,
                sessionId,
                targetId,
                new PredictionUpdateRequest(
                        List.of(Map.of("horizon", 1, "reason", "변경"))
                )
        );

        assertEquals("up", response.predictions().get(0).direction());
        assertEquals("변경", response.predictions().get(0).reason());
        assertNotNull(response.updatedAt());
        assertEquals(session.getUpdatedAt(), response.updatedAt());
        assertEquals(3, response.predictions().size());

        var explicitlyNull = new java.util.HashMap<String, Object>();
        explicitlyNull.put("horizon", 1);
        explicitlyNull.put("reason", null);

        service.update(
                userId,
                sessionId,
                targetId,
                new PredictionUpdateRequest(List.of(explicitlyNull))
        );

        assertNull(original.getReason());
        assertEquals(Prediction.Direction.up, original.getDirection());
    }

    @Test
    void emptyDraftAndReadReturnThreeHorizons() {
        when(predictionRepo.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of());

        service.update(
                userId,
                sessionId,
                targetId,
                new PredictionUpdateRequest(List.of())
        );

        PredictionResponse response =
                service.findByTarget(userId, sessionId, targetId);

        assertEquals(
                List.of(1, 5, 20),
                response.predictions().stream()
                        .map(p -> p.horizon())
                        .toList()
        );

        assertTrue(
                response.predictions().stream()
                        .allMatch(p -> p.direction() == null)
        );
    }

    @Test
    void duplicateHorizonRejectedWithoutPersist() {
        var patch = Map.<String, Object>of(
                "horizon", 1,
                "direction", "up"
        );

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.update(
                        userId,
                        sessionId,
                        targetId,
                        new PredictionUpdateRequest(List.of(patch, patch))
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(predictionRepo, never()).save(any());
    }

    @Test
    void submitRequiresAtLeastOneReason() {
        when(targetRepo.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        var predictions = fullPredictions(false);

        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId)))
                .thenReturn(predictions);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.submit(userId, sessionId)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(
                LearningSession.LearningStatus.drafting,
                session.getStatus()
        );

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void successfulSubmitFreezesAllDirectionsAndEmitsIntegrationEvent() {
        when(targetRepo.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        var predictions = fullPredictions(true);

        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId)))
                .thenReturn(predictions);

        var response = service.submit(userId, sessionId);

        assertEquals("baseline_pending", response.status());
        assertNotNull(response.submittedAt());

        assertEquals(
                response.submittedAt(),
                session.getSubmittedAt()
        );

        assertEquals(
                articleReferenceAt,
                session.getReplayReferenceAt().toInstant()
        );

        assertTrue(
                predictions.stream().allMatch(
                        p -> response.submittedAt().equals(p.getSubmittedAt())
                )
        );

        verify(publisher).publishEvent(
                new PredictionSubmittedEvent(
                        sessionId,
                        userId,
                        response.submittedAt(),
                        session.getReplayReferenceAt()
                )
        );

        ApiException conflict = assertThrows(
                ApiException.class,
                () -> service.submit(userId, sessionId)
        );

        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
    }

    @Test
    void submitRejectsMissingArticleReferenceWithoutFreezingPredictions() {
        when(targetRepo.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        var predictions = fullPredictions(true);

        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId)))
                .thenReturn(predictions);

        when(news.getReferenceAt()).thenReturn(null);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.submit(userId, sessionId)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());

        assertEquals(
                LearningSession.LearningStatus.drafting,
                session.getStatus()
        );

        assertTrue(
                predictions.stream().allMatch(
                        p -> p.getSubmittedAt() == null
                )
        );

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void replaySubmitStoresHistoricalReferenceSeparatelyFromSubmissionTime() {
        LearningSession replay = LearningSession.create(
                userId,
                session.getNewsId(),
                LearningSession.SessionType.replay
        );

        var reference = Instant.parse("2024-01-03T12:00:00Z");

        var replayNews = mock(News.class);
        when(replayNews.getReferenceAt()).thenReturn(reference);

        when(entityManager.find(News.class, replay.getNewsId()))
                .thenReturn(replayNews);

        when(sessionRepo.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(replay));

        when(targetRepo.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId)))
                .thenReturn(fullPredictions(true));

        var response = service.submit(userId, sessionId);

        assertEquals("baseline_pending", response.status());

        assertEquals(
                reference,
                replay.getReplayReferenceAt().toInstant()
        );

        assertEquals(
                response.submittedAt(),
                replay.getSubmittedAt()
        );

        assertTrue(
                response.submittedAt().toInstant().isAfter(reference)
        );

        assertNull(replay.getResultsRevealedAt());

        verify(publisher).publishEvent(
                new PredictionSubmittedEvent(
                        sessionId,
                        userId,
                        response.submittedAt(),
                        replay.getReplayReferenceAt()
                )
        );
    }

    @Test
    void wrongOwnerDenied() {
        ApiException error = assertThrows(
                ApiException.class,
                () -> service.findByTarget(
                        UUID.randomUUID(),
                        sessionId,
                        targetId
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());

        assertEquals(
                "해당 예측을 조회할 권한이 없습니다.",
                error.getMessage()
        );
    }

    @Test
    void submitWrongOwnerDenied() {
        UUID otherUserId = UUID.randomUUID();

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.submit(otherUserId, sessionId)
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());

        assertEquals(
                "해당 학습 세션을 제출할 권한이 없습니다.",
                error.getMessage()
        );

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void submitRejectsMarketTargetNotInNewsCandidates() {
        when(targetRepo.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of(target));

        when(candidateQuery.getSingleResult()).thenReturn(0L);

        ApiException error = assertThrows(
                ApiException.class,
                () -> service.submit(userId, sessionId)
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());

        assertEquals(
                LearningSession.LearningStatus.drafting,
                session.getStatus()
        );

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private List<Prediction> fullPredictions(boolean hasReason) {
        return List.of(1, 5, 20).stream()
                .map(horizon -> {
                    var p = Prediction.create(targetId, horizon);

                    p.update(
                            true,
                            Prediction.Direction.up,
                            true,
                            (hasReason && horizon == 1)
                                    ? "금리 영향을 고려했습니다."
                                    : null
                    );

                    return p;
                })
                .toList();
    }
}
