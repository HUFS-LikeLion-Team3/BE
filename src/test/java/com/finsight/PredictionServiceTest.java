package com.finsight;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
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

import java.lang.reflect.Field;
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
    private LearningSession session;
    private SessionTarget target;
    private LearningSessionRepository sessionRepo;
    private SessionTargetRepository targetRepo;
    private PredictionRepository predictionRepo;
    private ApplicationEventPublisher publisher;
    private PredictionService service;

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
        service = new PredictionService(sessionRepo, targetRepo, predictionRepo, publisher);

        when(sessionRepo.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(target));
    }

    @Test
    void partialUpdatePreservesOmittedDirectionAndClearsExplicitNullReason() {
        Prediction original = Prediction.create(targetId, 1);
        original.update(true, Prediction.Direction.up, true, "이유 보존?");
        when(predictionRepo.findBySessionTargetIdAndHorizon(targetId, (short) 1))
                .thenReturn(Optional.of(original));
        when(predictionRepo.findAllBySessionTargetIdOrderByHorizonAsc(targetId))
                .thenReturn(List.of(original));

        var response = service.update(userId, sessionId, targetId,
                new PredictionUpdateRequest(List.of(Map.of("horizon", 1, "reason", "변경"))));
        assertEquals("up", response.predictions().get(0).direction());
        assertEquals("변경", response.predictions().get(0).reason());
        assertNotNull(response.updatedAt());
        assertEquals(3, response.predictions().size());

        var explicitlyNull = new java.util.HashMap<String, Object>();
        explicitlyNull.put("horizon", 1);
        explicitlyNull.put("reason", null);
        service.update(userId, sessionId, targetId,
                new PredictionUpdateRequest(List.of(explicitlyNull)));
        assertNull(original.getReason());
        assertEquals(Prediction.Direction.up, original.getDirection());
    }

    @Test
    void emptyDraftAndReadReturnThreeHorizons() {
        when(predictionRepo.findAllBySessionTargetIdOrderByHorizonAsc(targetId)).thenReturn(List.of());
        service.update(userId, sessionId, targetId, new PredictionUpdateRequest(List.of()));
        PredictionResponse response = service.findByTarget(userId, sessionId, targetId);
        assertEquals(List.of(1, 5, 20), response.predictions().stream().map(p -> p.horizon()).toList());
        assertTrue(response.predictions().stream().allMatch(p -> p.direction() == null));
    }

    @Test
    void duplicateHorizonRejectedWithoutPersist() {
        var patch = Map.<String, Object>of("horizon", 1, "direction", "up");
        ApiException error = assertThrows(ApiException.class, () -> service.update(
                userId, sessionId, targetId,
                new PredictionUpdateRequest(List.of(patch, patch))));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(predictionRepo, never()).save(any());
    }

    @Test
    void submitRequiresAtLeastOneReason() {
        when(targetRepo.findAllByLearningSessionId(sessionId)).thenReturn(List.of(target));
        var predictions = fullPredictions(false);
        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId))).thenReturn(predictions);
        ApiException error = assertThrows(ApiException.class, () -> service.submit(userId, sessionId));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals(LearningSession.LearningStatus.drafting, session.getStatus());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void successfulSubmitFreezesAllDirectionsAndEmitsIntegrationEvent() {
        when(targetRepo.findAllByLearningSessionId(sessionId)).thenReturn(List.of(target));
        var predictions = fullPredictions(true);
        when(predictionRepo.findAllBySessionTargetIdIn(List.of(targetId))).thenReturn(predictions);
        var response = service.submit(userId, sessionId);
        assertEquals("baseline_pending", response.status());
        assertNotNull(response.submittedAt());
        assertEquals(response.submittedAt(), session.getSubmittedAt());
        assertTrue(predictions.stream().allMatch(p -> response.submittedAt().equals(p.getSubmittedAt())));
        verify(publisher).publishEvent(any(PredictionSubmittedEvent.class));
        ApiException conflict = assertThrows(ApiException.class, () -> service.submit(userId, sessionId));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
    }

    @Test
    void wrongOwnerDenied() {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        ApiException error = assertThrows(ApiException.class, () -> service.findByTarget(
                UUID.randomUUID(), sessionId, targetId));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    private List<Prediction> fullPredictions(boolean hasReason) {
        return List.of(1, 5, 20).stream().map(horizon -> {
            var p = Prediction.create(targetId, horizon);
            p.update(true, Prediction.Direction.up, true,
                    (hasReason && horizon == 1) ? "금리 영향을 고려했습니다." : null);
            return p;
        }).toList();
    }
}
