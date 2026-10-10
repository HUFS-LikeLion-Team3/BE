package com.finsight.prediction.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.prediction.dto.*;
import com.finsight.prediction.entity.Prediction;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.event.PredictionSubmittedEvent;
import com.finsight.prediction.repository.PredictionRepository;
import com.finsight.prediction.repository.SessionTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PredictionService {
    private static final List<Integer> HORIZONS = List.of(1, 5, 20);
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final LearningSessionRepository learningSessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final PredictionRepository predictionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PredictionUpdateResponse update(
            UUID userId, UUID sessionId, UUID targetId, PredictionUpdateRequest request
    ) {
        // Lock the session so concurrent saves/submissions of predictions serialize.
        LearningSession session = getSessionForUpdate(sessionId);
        validateOwner(session, userId);
        validateDrafting(session);
        SessionTarget target = getTarget(sessionId, targetId);
        if (!target.isSelected()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "선택 해제된 예측 대상은 수정할 수 없습니다.");
        }

        List<PredictionPatch> patches = parsePatches(request);
        for (PredictionPatch patch : patches) {
            Prediction prediction = predictionRepository
                    .findBySessionTargetIdAndHorizon(targetId, (short) patch.horizon())
                    .orElseGet(() -> Prediction.create(targetId, patch.horizon()));
            prediction.update(patch.hasDirection(), patch.direction(),
                    patch.hasReason(), patch.reason());
            predictionRepository.save(prediction);
        }
        session.markSaved();
        return new PredictionUpdateResponse(targetId, allThree(targetId), session.getSavedAt());
    }

    public PredictionResponse findByTarget(UUID userId, UUID sessionId, UUID targetId) {
        LearningSession session = getSession(sessionId);
        validateOwner(session, userId);
        // Deselecting a target does not erase its saved predictions.
        getTarget(sessionId, targetId);
        return new PredictionResponse(targetId, allThree(targetId));
    }

    @Transactional
    public PredictionSubmitResponse submit(UUID userId, UUID sessionId) {
        LearningSession session = getSessionForUpdate(sessionId);
        validateOwner(session, userId);
        if (session.getStatus() != LearningSession.LearningStatus.drafting) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 최종 제출된 학습 세션입니다.");
        }

        List<SessionTarget> selectedTargets = sessionTargetRepository
                .findAllByLearningSessionId(sessionId).stream()
                .filter(SessionTarget::isSelected).toList();
        if (selectedTargets.isEmpty() || selectedTargets.size() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "예측 대상은 1개 이상 3개 이하로 선택해야 합니다.");
        }

        // News candidate validation belongs to the Market/News integration (same TODO as part 6).
        List<UUID> targetIds = selectedTargets.stream().map(SessionTarget::getId).toList();
        List<Prediction> predictions = predictionRepository.findAllBySessionTargetIdIn(targetIds);
        Map<UUID, Map<Integer, Prediction>> indexed = predictions.stream()
                .collect(Collectors.groupingBy(Prediction::getSessionTargetId,
                        Collectors.toMap(p -> p.getHorizon().intValue(), Function.identity())));

        boolean hasAnyReason = false;
        for (SessionTarget target : selectedTargets) {
            Map<Integer, Prediction> byHorizon = indexed.getOrDefault(target.getId(), Map.of());
            for (int horizon : HORIZONS) {
                Prediction prediction = byHorizon.get(horizon);
                if (prediction == null || prediction.getDirection() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "선택한 모든 대상의 T1, T5, T20 방향을 입력해야 합니다.");
                }
                if (prediction.getReason() != null && !prediction.getReason().isBlank()) {
                    hasAnyReason = true;
                }
            }
        }
        if (!hasAnyReason) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "AI 피드백을 위해 최소 1개의 판단 이유를 작성해야 합니다.");
        }

        OffsetDateTime submittedAt = OffsetDateTime.now(KOREA_ZONE);
        for (Prediction prediction : predictions) {
            prediction.markSubmitted(submittedAt);
        }
        session.markSubmitted(submittedAt);
        // Listeners must use @TransactionalEventListener(AFTER_COMMIT) for T0/AI requests.
        eventPublisher.publishEvent(new PredictionSubmittedEvent(sessionId, userId, submittedAt));
        return new PredictionSubmitResponse(sessionId, session.getStatus().name(), submittedAt);
    }

    private List<PredictionItemResponse> allThree(UUID targetId) {
        Map<Integer, Prediction> saved = predictionRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(targetId).stream()
                .collect(Collectors.toMap(p -> p.getHorizon().intValue(), Function.identity()));
        return HORIZONS.stream()
                .map(horizon -> saved.containsKey(horizon)
                        ? PredictionItemResponse.from(saved.get(horizon))
                        : PredictionItemResponse.empty(horizon))
                .toList();
    }

    private LearningSession getSession(UUID sessionId) {
        return learningSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "학습 세션을 찾을 수 없습니다."));
    }

    private LearningSession getSessionForUpdate(UUID sessionId) {
        return learningSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "학습 세션을 찾을 수 없습니다."));
    }

    private SessionTarget getTarget(UUID sessionId, UUID targetId) {
        SessionTarget target = sessionTargetRepository.findById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "학습 세션 또는 예측 대상을 찾을 수 없습니다."));
        if (!target.getLearningSessionId().equals(sessionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "학습 세션 또는 예측 대상을 찾을 수 없습니다.");
        }
        return target;
    }

    private void validateOwner(LearningSession session, UUID userId) {
        if (!session.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "해당 학습 세션에 접근할 권한이 없습니다.");
        }
    }

    private void validateDrafting(LearningSession session) {
        if (session.getStatus() != LearningSession.LearningStatus.drafting) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "현재 상태에서는 예측을 수정할 수 없습니다.");
        }
    }

    private List<PredictionPatch> parsePatches(PredictionUpdateRequest request) {
        if (request == null || request.predictions() == null) {
            throw badPredictionRequest();
        }
        Set<Integer> horizons = new HashSet<>();
        List<PredictionPatch> patches = new ArrayList<>();
        for (Map<String, Object> raw : request.predictions()) {
            if (raw == null || !raw.keySet().stream()
                    .allMatch(Set.of("horizon", "direction", "reason")::contains)) {
                throw badPredictionRequest();
            }
            Object h = raw.get("horizon");
            if (!(h instanceof Integer horizon) || !HORIZONS.contains(horizon) || !horizons.add(horizon)) {
                throw badPredictionRequest();
            }
            boolean hasDirection = raw.containsKey("direction");
            Prediction.Direction direction = null;
            if (hasDirection && raw.get("direction") != null) {
                if (!(raw.get("direction") instanceof String value)) throw badPredictionRequest();
                try {
                    direction = Prediction.Direction.valueOf(value);
                } catch (IllegalArgumentException e) {
                    throw badPredictionRequest();
                }
            }
            boolean hasReason = raw.containsKey("reason");
            String reason = null;
            if (hasReason && raw.get("reason") != null) {
                if (!(raw.get("reason") instanceof String value)) throw badPredictionRequest();
                reason = value.isBlank() ? null : value;
            }
            patches.add(new PredictionPatch(horizon, hasDirection, direction, hasReason, reason));
        }
        return patches;
    }

    private ApiException badPredictionRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, "예측 기간 또는 예측 방향이 올바르지 않습니다.");
    }

    private record PredictionPatch(
            int horizon, boolean hasDirection, Prediction.Direction direction,
            boolean hasReason, String reason
    ) { }
}
