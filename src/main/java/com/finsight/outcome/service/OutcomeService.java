
package com.finsight.outcome.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.dto.OutcomeEventResponse;
import com.finsight.outcome.dto.OutcomeResponse;
import com.finsight.outcome.dto.OutcomeSeriesResponse;
import com.finsight.outcome.dto.TargetOutcomeResponse;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.OutcomeCollectionEvent;
import com.finsight.outcome.entity.OutcomeSeriesPoint;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.OutcomeCollectionEventRepository;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OutcomeService {

    private static final List<Integer> HORIZONS =
            List.of(1, 5, 20);

    private final LearningSessionRepository learningSessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final MarketTargetRepository marketTargetRepository;
    private final SessionTargetBaselineRepository sessionTargetBaselineRepository;
    private final MarketObservationRepository marketObservationRepository;
    private final OutcomeSeriesPointRepository outcomeSeriesPointRepository;
    private final OutcomeCollectionEventRepository outcomeCollectionEventRepository;

    public OutcomeResponse findAll(
            UUID userId,
            UUID learningSessionId
    ) {
        LearningSession session = requireSession(
                userId,
                learningSessionId,
                "해당 학습 세션을 찾을 수 없습니다.",
                "해당 학습 세션의 시장 결과에 접근할 권한이 없습니다."
        );

        requireSubmitted(
                session,
                "해당 학습 세션의 시장 결과에 접근할 권한이 없습니다."
        );

        List<SessionTarget> selectedTargets =
                sessionTargetRepository
                        .findAllByLearningSessionId(learningSessionId)
                        .stream()
                        .filter(SessionTarget::isSelected)
                        .sorted(Comparator.comparing(
                                SessionTarget::getSortOrder
                        ))
                        .toList();

        List<OutcomeResponse.TargetResponse> targets =
                selectedTargets.stream()
                        .map(target -> {
                            OutcomeView view = loadView(
                                    session,
                                    target
                            );

                            return new OutcomeResponse.TargetResponse(
                                    target.getId(),
                                    target.getMarketTargetId(),
                                    view.baseline(),
                                    view.observations()
                            );
                        })
                        .toList();

        return new OutcomeResponse(
                learningSessionId,
                targets
        );
    }

    public TargetOutcomeResponse findOne(
            UUID userId,
            UUID learningSessionId,
            UUID sessionTargetId
    ) {
        LearningSession session = requireSession(
                userId,
                learningSessionId,
                "학습 세션 또는 예측 대상을 찾을 수 없습니다.",
                "해당 시장 결과에 접근할 권한이 없습니다."
        );

        requireSubmitted(
                session,
                "해당 시장 결과에 접근할 권한이 없습니다."
        );

        SessionTarget target = findSelectedTarget(
                learningSessionId,
                sessionTargetId
        );

        OutcomeView view = loadView(session, target);

        return new TargetOutcomeResponse(
                learningSessionId,
                target.getId(),
                target.getMarketTargetId(),
                view.baseline(),
                view.observations()
        );
    }

    public OutcomeSeriesResponse findSeries(
            UUID userId,
            UUID learningSessionId,
            UUID sessionTargetId
    ) {
        LearningSession session = requireSession(
                userId,
                learningSessionId,
                "학습 세션 또는 예측 대상을 찾을 수 없습니다.",
                "해당 시장 결과에 접근할 권한이 없습니다."
        );

        requireSubmitted(
                session,
                "해당 시장 결과에 접근할 권한이 없습니다."
        );

        SessionTarget target = findSelectedTarget(
                learningSessionId,
                sessionTargetId
        );

        List<OutcomeSeriesPoint> seriesPoints =
                outcomeSeriesPointRepository
                        .findAllBySessionTargetIdOrderByTradingDateAsc(
                                sessionTargetId
                        );

        return OutcomeSeriesResponse.from(
                learningSessionId,
                target,
                seriesPoints
        );
    }

    public List<OutcomeEventResponse> findEvents(
            UUID userId,
            UUID sessionTargetId
    ) {
        SessionTarget target =
                sessionTargetRepository.findById(sessionTargetId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "요청한 세션 또는 기록을 찾을 수 없습니다."
                        ));

        LearningSession session =
                learningSessionRepository
                        .findById(target.getLearningSessionId())
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "요청한 세션 또는 기록을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 기록에 접근할 권한이 없습니다."
            );
        }

        if (session.getSubmittedAt() == null
                || !target.isSelected()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "요청한 세션 또는 기록을 찾을 수 없습니다."
            );
        }

        List<OutcomeCollectionEvent> events =
                outcomeCollectionEventRepository
                        .findAllBySessionTargetIdOrderByOccurredAtAscIdAsc(
                                sessionTargetId
                        );

        return events.stream()
                .map(OutcomeEventResponse::from)
                .toList();
    }

    private OutcomeView loadView(
            LearningSession session,
            SessionTarget target
    ) {
        SessionTargetBaseline baseline =
                sessionTargetBaselineRepository
                        .findBySessionTargetId(target.getId())
                        .orElse(null);

        if (baseline == null
                && session.getStatus()
                != LearningSession.LearningStatus.baseline_pending) {
            throw new IllegalStateException(
                    "Missing baseline for non-pending session"
            );
        }

        String unit = baseline != null
                ? baseline.getUnit()
                : requireMarketTarget(target).getUnit();

        OutcomeResponse.BaselineResponse baselineResponse =
                baseline == null
                        ? new OutcomeResponse.BaselineResponse(
                        "pending",
                        null,
                        null,
                        null,
                        unit,
                        null,
                        null,
                        null,
                        null,
                        null
                )
                        : OutcomeResponse.BaselineResponse.from(baseline);

        List<MarketObservation> storedObservations =
                marketObservationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                target.getId()
                        );

        Map<Integer, MarketObservation> byHorizon =
                new HashMap<>();

        for (MarketObservation observation : storedObservations) {
            if (!HORIZONS.contains(observation.getHorizon())) {
                throw new IllegalStateException(
                        "Invalid stored observation horizon"
                );
            }

            MarketObservation existing = byHorizon.putIfAbsent(
                    observation.getHorizon(),
                    observation
            );

            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate observation horizon"
                );
            }
        }

        boolean mustHaveAllObservations =
                (baseline != null
                        && baseline.getStatus()
                        == SessionTargetBaseline.BaselineStatus.unavailable)
                        || session.getStatus()
                        == LearningSession.LearningStatus.reflection_pending
                        || session.getStatus()
                        == LearningSession.LearningStatus.completed;

        List<OutcomeResponse.ObservationResponse> observations =
                new ArrayList<>();

        for (int horizon : HORIZONS) {
            MarketObservation observation = byHorizon.get(horizon);

            if (observation != null) {
                observations.add(
                        OutcomeResponse.ObservationResponse.from(
                                observation
                        )
                );
                continue;
            }

            if (mustHaveAllObservations) {
                throw new IllegalStateException(
                        "Missing finalized observation: T" + horizon
                );
            }

            observations.add(
                    pendingObservation(
                            horizon,
                            baseline,
                            unit
                    )
            );
        }

        return new OutcomeView(
                baselineResponse,
                List.copyOf(observations)
        );
    }

    private OutcomeResponse.ObservationResponse pendingObservation(
            int horizon,
            SessionTargetBaseline baseline,
            String unit
    ) {
        return new OutcomeResponse.ObservationResponse(
                horizon,
                "pending",
                "none",
                baseline != null
                        ? baseline.getBaselineAt()
                        : null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                false,
                unit,
                null,
                null,
                null
        );
    }

    private LearningSession requireSession(
            UUID userId,
            UUID learningSessionId,
            String notFoundMessage,
            String forbiddenMessage
    ) {
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                notFoundMessage
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    forbiddenMessage
            );
        }

        return session;
    }

    private void requireSubmitted(
            LearningSession session,
            String forbiddenMessage
    ) {
        if (session.getSubmittedAt() == null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    forbiddenMessage
            );
        }
    }

    private SessionTarget findSelectedTarget(
            UUID learningSessionId,
            UUID sessionTargetId
    ) {
        return sessionTargetRepository
                .findAllByLearningSessionId(learningSessionId)
                .stream()
                .filter(target ->
                        target.getId().equals(sessionTargetId)
                )
                .filter(SessionTarget::isSelected)
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "학습 세션 또는 예측 대상을 찾을 수 없습니다."
                ));
    }

    private MarketTarget requireMarketTarget(
            SessionTarget target
    ) {
        return marketTargetRepository
                .findById(target.getMarketTargetId())
                .orElseThrow(() -> new IllegalStateException(
                        "Market target does not exist: "
                                + target.getMarketTargetId()
                ));
    }

    private record OutcomeView(
            OutcomeResponse.BaselineResponse baseline,
            List<OutcomeResponse.ObservationResponse> observations
    ) {
    }
}
