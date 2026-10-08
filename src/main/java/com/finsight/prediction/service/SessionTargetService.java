package com.finsight.prediction.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.prediction.dto.SessionTargetResponse;
import com.finsight.prediction.dto.SessionTargetUpdateRequest;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionTargetService {

    private final LearningSessionRepository learningSessionRepository;
    private final SessionTargetRepository sessionTargetRepository;

    public SessionTargetResponse findAll(
            UUID userId,
            UUID learningSessionId
    ) {
        LearningSession session = getSession(learningSessionId);

        validateOwner(session, userId);

        List<SessionTarget> sessionTargets =
                sessionTargetRepository.findAllByLearningSessionId(
                        learningSessionId
                );

        return SessionTargetResponse.from(
                learningSessionId,
                sessionTargets
        );
    }

    @Transactional
    public SessionTargetResponse update(
            UUID userId,
            UUID learningSessionId,
            SessionTargetUpdateRequest request
    ) {
        LearningSession session = getSession(learningSessionId);

        validateOwner(session, userId);
        validateModifiable(session);
        validateRequest(request);

        /*
         * TODO:
         * market/news 도메인 코드가 develop에 합쳐진 뒤
         * 모든 marketTargetId가 session.newsId의
         * news_target_candidates인지 검증해야 함.
         */

        List<SessionTarget> existingTargets =
                sessionTargetRepository.findAllByLearningSessionId(
                        learningSessionId
                );

        for (SessionTarget sessionTarget : existingTargets) {
            sessionTarget.deselect();
        }

        /*
         * (learning_session_id, sort_order) UNIQUE 충돌을 피하기 위해
         * 기존 sortOrder를 먼저 모두 NULL로 DB에 반영한다.
         */
        sessionTargetRepository.flush();

        for (SessionTargetUpdateRequest.TargetRequest target
                : request.targets()) {

            SessionTarget sessionTarget =
                    sessionTargetRepository
                            .findByLearningSessionIdAndMarketTargetId(
                                    learningSessionId,
                                    target.marketTargetId()
                            )
                            .orElseGet(() ->
                                    SessionTarget.create(
                                            learningSessionId,
                                            target.marketTargetId(),
                                            target.sortOrder()
                                    )
                            );

            sessionTarget.select(target.sortOrder());

            sessionTargetRepository.save(sessionTarget);
        }

        session.markSaved();

        List<SessionTarget> result =
                sessionTargetRepository.findAllByLearningSessionId(
                        learningSessionId
                );

        return SessionTargetResponse.from(
                learningSessionId,
                result
        );
    }

    private LearningSession getSession(
            UUID learningSessionId
    ) {
        return learningSessionRepository.findById(learningSessionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "해당 학습 세션을 찾을 수 없습니다."
                ));
    }

    private void validateOwner(
            LearningSession session,
            UUID userId
    ) {
        if (!session.getUserId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "해당 학습 세션에 접근할 권한이 없습니다."
            );
        }
    }

    private void validateModifiable(
            LearningSession session
    ) {
        if (session.getStatus()
                != LearningSession.LearningStatus.drafting) {

            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "현재 상태에서는 예측 대상을 수정할 수 없습니다."
            );
        }
    }

    private void validateRequest(
            SessionTargetUpdateRequest request
    ) {
        if (request == null || request.targets() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "잘못된 요청입니다."
            );
        }

        if (request.targets().size() > 3) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "예측 대상은 최대 3개까지 선택할 수 있습니다."
            );
        }

        Set<UUID> marketTargetIds = new HashSet<>();
        Set<Integer> sortOrders = new HashSet<>();

        for (SessionTargetUpdateRequest.TargetRequest target
                : request.targets()) {

            if (target == null
                    || target.marketTargetId() == null
                    || target.sortOrder() == null) {

                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "잘못된 요청입니다."
                );
            }

            if (!marketTargetIds.add(target.marketTargetId())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "잘못된 요청입니다."
                );
            }

            if (target.sortOrder() < 1
                    || target.sortOrder() > 3
                    || !sortOrders.add(target.sortOrder())) {

                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "선택 순서는 1부터 3까지 중복 없이 지정해야 합니다."
                );
            }
        }
    }
}
