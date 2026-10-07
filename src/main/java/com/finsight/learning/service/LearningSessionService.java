package com.finsight.learning.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.dto.LearningSessionListItemResponse;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningSessionService {

    private final LearningSessionRepository learningSessionRepository;

    @Transactional
    public SessionResult createOrGet(
            UUID userId,
            UUID newsId,
            String sessionType
    ) {
        if (!"live".equals(sessionType)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "올바르지 않은 학습 세션 유형입니다."
            );
        }

        return learningSessionRepository.findByUserIdAndNewsId(userId, newsId)
                .map(session -> {
                    if (!session.getSessionType().name().equals(sessionType)) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "기존 학습 세션의 유형과 일치하지 않습니다."
                        );
                    }

                    return new SessionResult(session, false);
                })
                .orElseGet(() -> {
                    LearningSession learningSession =
                            LearningSession.create(userId, newsId);

                    return new SessionResult(
                            learningSessionRepository.save(learningSession),
                            true
                    );
                });
    }

    public Page<LearningSessionListItemResponse> findAll(
            UUID userId,
            UUID newsId,
            LearningSession.LearningStatus status,
            LearningSession.SessionType sessionType,
            Pageable pageable
    ) {
        return learningSessionRepository.findByCondition(
                        userId,
                        newsId,
                        status,
                        sessionType,
                        pageable
                )
                .map(LearningSessionListItemResponse::from);
    }

    public record SessionResult(
            LearningSession session,
            boolean created
    ) {
    }
}
