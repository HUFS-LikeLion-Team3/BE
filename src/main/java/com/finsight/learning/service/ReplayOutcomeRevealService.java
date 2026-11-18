package com.finsight.learning.service;

import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/** The reveal click is separate from market collection and session status transitions. */
@Service
@RequiredArgsConstructor
public class ReplayOutcomeRevealService {
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final LearningSessionRepository sessionRepository;
    private final AiFeedbackRepository feedbackRepository;

    @Transactional
    public OffsetDateTime reveal(UUID userId, UUID sessionId) {
        LearningSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "학습 세션을 찾을 수 없습니다."));

        if (!session.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "해당 학습 세션에 접근할 권한이 없습니다.");
        }
        if (session.getSessionType() != LearningSession.SessionType.replay) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Replay 학습 세션에서만 결과를 공개할 수 있습니다.");
        }
        if (session.getSubmittedAt() == null
                || session.getStatus() == LearningSession.LearningStatus.drafting) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "예측을 최종 제출한 후 결과를 확인할 수 있습니다.");
        }

        // Idempotent: preserve the original click time on retries and revisits.
        if (session.getResultsRevealedAt() != null) {
            return session.getResultsRevealedAt();
        }

        // FE calls this endpoint only after the user has inspected INITIAL feedback.
        // BE additionally rejects the reveal while INITIAL is not ready.
        boolean initialReady = feedbackRepository.findAllByLearningSessionId(sessionId)
                .stream()
                .anyMatch(feedback -> feedback.getFeedbackStage() == AiFeedback.FeedbackStage.INITIAL
                        && Boolean.TRUE.equals(feedback.getIsLatest())
                        && feedback.getStatus() == AiFeedback.FeedbackStatus.ready);
        if (!initialReady) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "최초 AI 피드백이 준비된 후 시장 결과를 확인할 수 있습니다.");
        }

        session.revealReplayResults(OffsetDateTime.now(KOREA_ZONE));
        return session.getResultsRevealedAt();
    }
}
