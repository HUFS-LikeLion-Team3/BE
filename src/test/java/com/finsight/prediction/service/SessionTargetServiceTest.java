
package com.finsight.prediction.service;

import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.prediction.dto.SessionTargetUpdateRequest;
import com.finsight.prediction.repository.SessionTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionTargetServiceTest {

    @Mock
    private LearningSessionRepository learningSessionRepository;

    @Mock
    private SessionTargetRepository sessionTargetRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private LearningSession session;

    @Mock
    private TypedQuery<Long> marketTargetQuery;

    @Mock
    private TypedQuery<Long> candidateQuery;

    @InjectMocks
    private SessionTargetService sessionTargetService;

    @Test
    void rejectInvalidNewsCandidateWithoutChangingTargets() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID newsId = UUID.randomUUID();

        UUID validTargetId = UUID.randomUUID();
        UUID invalidTargetId = UUID.randomUUID();

        when(learningSessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getUserId()).thenReturn(userId);
        when(session.getStatus())
                .thenReturn(LearningSession.LearningStatus.drafting);
        when(session.getNewsId()).thenReturn(newsId);

        // 요청한 시장 대상 2개 모두 실제로 존재
        when(entityManager.createQuery(
                contains("FROM MarketTarget t"), eq(Long.class)
        )).thenReturn(marketTargetQuery);

        when(marketTargetQuery.setParameter(eq("targetIds"), any()))
                .thenReturn(marketTargetQuery);

        when(marketTargetQuery.getSingleResult())
                .thenReturn(2L);

        // 그중 해당 뉴스의 예측 후보는 1개뿐
        when(entityManager.createQuery(
                contains("FROM NewsTargetCandidate c"), eq(Long.class)
        )).thenReturn(candidateQuery);

        when(candidateQuery.setParameter("newsId", newsId))
                .thenReturn(candidateQuery);

        when(candidateQuery.setParameter(eq("targetIds"), any()))
                .thenReturn(candidateQuery);

        when(candidateQuery.getSingleResult())
                .thenReturn(1L);

        SessionTargetUpdateRequest request =
                new SessionTargetUpdateRequest(List.of(
                        new SessionTargetUpdateRequest.TargetRequest(
                                validTargetId, 1
                        ),
                        new SessionTargetUpdateRequest.TargetRequest(
                                invalidTargetId, 2
                        )
                ));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> sessionTargetService.update(
                        userId, sessionId, request
                )
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());

        assertEquals(
                "해당 뉴스의 예측 후보가 아닌 시장 대상이 포함되어 있습니다.",
                exception.getMessage()
        );

        // 기존 대상 조회, 해제, 저장 등이 시작되지 않아야 함
        verifyNoInteractions(sessionTargetRepository);

        // 잘못된 요청은 임시 저장 시각도 변경하지 않아야 함
        verify(session, never()).markSaved();
    }

    @Test
    void rejectNonexistentMarketTargetWithoutChangingTargets() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID nonexistentTargetId = UUID.randomUUID();

        when(learningSessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getUserId()).thenReturn(userId);
        when(session.getStatus())
                .thenReturn(LearningSession.LearningStatus.drafting);

        // 요청한 시장 대상이 DB에 존재하지 않는 상황
        when(entityManager.createQuery(
                contains("FROM MarketTarget t"), eq(Long.class)
        )).thenReturn(marketTargetQuery);

        when(marketTargetQuery.setParameter(eq("targetIds"), any()))
                .thenReturn(marketTargetQuery);

        when(marketTargetQuery.getSingleResult())
                .thenReturn(0L);

        SessionTargetUpdateRequest request =
                new SessionTargetUpdateRequest(List.of(
                        new SessionTargetUpdateRequest.TargetRequest(
                                nonexistentTargetId, 1
                        )
                ));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> sessionTargetService.update(
                        userId, sessionId, request
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());

        assertEquals(
                "학습 세션 또는 시장 대상을 찾을 수 없습니다.",
                exception.getMessage()
        );

        // 기존 선택 대상은 변경하지 않아야 함
        verifyNoInteractions(sessionTargetRepository);
        verify(session, never()).markSaved();
    }

    @Test
    void saveValidNewsCandidate() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID newsId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(learningSessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));

        when(session.getUserId()).thenReturn(userId);
        when(session.getStatus())
                .thenReturn(LearningSession.LearningStatus.drafting);
        when(session.getNewsId()).thenReturn(newsId);

        // 시장 대상이 실제로 존재
        when(entityManager.createQuery(
                contains("FROM MarketTarget t"), eq(Long.class)
        )).thenReturn(marketTargetQuery);

        when(marketTargetQuery.setParameter(eq("targetIds"), any()))
                .thenReturn(marketTargetQuery);

        when(marketTargetQuery.getSingleResult())
                .thenReturn(1L);

        // 해당 뉴스의 정상 예측 후보
        when(entityManager.createQuery(
                contains("FROM NewsTargetCandidate c"), eq(Long.class)
        )).thenReturn(candidateQuery);

        when(candidateQuery.setParameter("newsId", newsId))
                .thenReturn(candidateQuery);

        when(candidateQuery.setParameter(eq("targetIds"), any()))
                .thenReturn(candidateQuery);

        when(candidateQuery.getSingleResult())
                .thenReturn(1L);

        when(sessionTargetRepository.findAllByLearningSessionId(sessionId))
                .thenReturn(List.of());

        when(sessionTargetRepository
                .findByLearningSessionIdAndMarketTargetId(sessionId, targetId))
                .thenReturn(Optional.empty());

        SessionTargetUpdateRequest request =
                new SessionTargetUpdateRequest(List.of(
                        new SessionTargetUpdateRequest.TargetRequest(
                                targetId, 1
                        )
                ));

        assertDoesNotThrow(() ->
                sessionTargetService.update(userId, sessionId, request)
        );

        // 학습 세션 조회 시 비관적 락 메서드 사용 확인
        verify(learningSessionRepository).findByIdForUpdate(sessionId);

        // 정상 후보는 저장 로직까지 진행
        verify(sessionTargetRepository).save(any());
        verify(session).markSaved();
    }
}
