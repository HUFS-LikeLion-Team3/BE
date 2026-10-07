package com.finsight.feedback.service;

import com.finsight.feedback.dto.FeedbackListItemResponse;
import com.finsight.feedback.entity.AiFeedback;
import com.finsight.feedback.repository.AiFeedbackRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
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
public class FeedbackService {

    private final AiFeedbackRepository aiFeedbackRepository;
    private final LearningSessionRepository learningSessionRepository;

    public List<FeedbackListItemResponse> findAll(
            UUID userId,
            UUID learningSessionId
    ) {
        LearningSession session =
                learningSessionRepository.findById(learningSessionId)
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

        return aiFeedbackRepository.findAllByLearningSessionId(learningSessionId)
                .stream()
                .sorted(
                        Comparator
                                .comparingInt((AiFeedback feedback) ->
                                        feedback.getFeedbackStage().ordinal())
                                .thenComparing(AiFeedback::getVersion)
                )
                .map(FeedbackListItemResponse::from)
                .toList();
    }
}