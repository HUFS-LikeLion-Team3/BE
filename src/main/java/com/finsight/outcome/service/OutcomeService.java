package com.finsight.outcome.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.outcome.dto.OutcomeResponse;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
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
    private final SessionTargetBaselineRepository
            sessionTargetBaselineRepository;
    private final MarketObservationRepository
            marketObservationRepository;

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

    private OutcomeResponse.TargetResponse createTargetResponse(
            SessionTarget sessionTarget
    ) {
        SessionTargetBaseline baseline =
                sessionTargetBaselineRepository
                        .findBySessionTargetId(sessionTarget.getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "시장 기준값 데이터가 존재하지 않습니다."
                                )
                        );

        List<MarketObservation> observations =
                marketObservationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTarget.getId()
                        );

        return OutcomeResponse.TargetResponse.from(
                sessionTarget,
                baseline,
                observations
        );
    }
}