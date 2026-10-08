package com.finsight.outcome.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OutcomeService {

    private final LearningSessionRepository learningSessionRepository;
    private final SessionTargetRepository sessionTargetRepository;
    private final SessionTargetBaselineRepository sessionTargetBaselineRepository;
    private final MarketObservationRepository marketObservationRepository;
    private final OutcomeSeriesPointRepository outcomeSeriesPointRepository;
    private final OutcomeCollectionEventRepository outcomeCollectionEventRepository;

    public OutcomeResponse findAll(
            UUID userId,
            UUID learningSessionId
    ) {
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "해당 학습 세션을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 학습 세션의 시장 결과에 접근할 권한이 없습니다."
            );
        }

        List<SessionTarget> selectedTargets =
                sessionTargetRepository
                        .findAllByLearningSessionId(learningSessionId)
                        .stream()
                        .filter(SessionTarget::isSelected)
                        .sorted(
                                Comparator.comparing(
                                        SessionTarget::getSortOrder
                                )
                        )
                        .toList();

        List<OutcomeResponse.TargetResponse> targets =
                selectedTargets.stream()
                        .map(this::createTargetResponse)
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
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "학습 세션 또는 예측 대상을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 시장 결과에 접근할 권한이 없습니다."
            );
        }

        SessionTarget sessionTarget =
                findSelectedTarget(
                        learningSessionId,
                        sessionTargetId
                );

        SessionTargetBaseline baseline =
                findBaseline(sessionTargetId);

        List<MarketObservation> observations =
                findObservations(sessionTargetId);

        return TargetOutcomeResponse.from(
                learningSessionId,
                sessionTarget,
                baseline,
                observations
        );
    }

    public OutcomeSeriesResponse findSeries(
            UUID userId,
            UUID learningSessionId,
            UUID sessionTargetId
    ) {
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "학습 세션 또는 예측 대상을 찾을 수 없습니다."
                        ));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 시장 결과에 접근할 권한이 없습니다."
            );
        }

        if (session.getSubmittedAt() == null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 시장 결과에 접근할 권한이 없습니다."
            );
        }

        SessionTarget sessionTarget =
                findSelectedTarget(
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
                sessionTarget,
                seriesPoints
        );
    }

    public List<OutcomeEventResponse> findEvents(
            UUID userId,
            UUID sessionTargetId
    ) {
        SessionTarget sessionTarget =
                sessionTargetRepository.findById(sessionTargetId)
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND,
                                "요청한 세션 또는 기록을 찾을 수 없습니다."
                        ));

        LearningSession session =
                learningSessionRepository
                        .findById(sessionTarget.getLearningSessionId())
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
                || !sessionTarget.isSelected()) {
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

    private OutcomeResponse.TargetResponse createTargetResponse(
            SessionTarget sessionTarget
    ) {
        SessionTargetBaseline baseline =
                findBaseline(sessionTarget.getId());

        List<MarketObservation> observations =
                findObservations(sessionTarget.getId());

        return OutcomeResponse.TargetResponse.from(
                sessionTarget,
                baseline,
                observations
        );
    }

    private SessionTargetBaseline findBaseline(
            UUID sessionTargetId
    ) {
        return sessionTargetBaselineRepository
                .findBySessionTargetId(sessionTargetId)
                .orElseThrow(() -> new IllegalStateException(
                        "시장 기준값 데이터가 존재하지 않습니다."
                ));
    }

    private List<MarketObservation> findObservations(
            UUID sessionTargetId
    ) {
        return marketObservationRepository
                .findAllBySessionTargetIdOrderByHorizonAsc(
                        sessionTargetId
                );
    }
}