package com.finsight.pattern;

import com.finsight.global.exception.ApiException;
import com.finsight.global.security.CurrentUser;
import com.finsight.learning.entity.LearningSession;
import com.finsight.pattern.controller.OutcomePatternController;
import com.finsight.pattern.dto.OutcomePatternResponse;
import com.finsight.pattern.dto.OutcomePatternRow;
import com.finsight.pattern.dto.OutcomeReflectionRow;
import com.finsight.pattern.repository.OutcomePatternRepository;
import com.finsight.pattern.service.OutcomePatternService;
import com.finsight.prediction.entity.Prediction;
import com.finsight.reflection.entity.ReflectionNote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutcomePatternServiceTest {
    @Mock OutcomePatternRepository repository;

    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC);

    private OutcomePatternService service() {
        return new OutcomePatternService(repository);
    }

    private OutcomePatternRow row(UUID session, UUID target, UUID news, int minutesAgo,
                                  LearningSession.SessionType type, OffsetDateTime revealedAt) {
        return new OutcomePatternRow(session, target, news, "뉴스 " + news.toString().substring(0, 4),
                "NASDAQ100", "NASDAQ 100", "point", 0, (short) 5, Prediction.Direction.up, "금리 인상 우려",
                LocalDate.of(2026, 9, 16), new BigDecimal("28945"), LocalDate.of(2026, 9, 23),
                new BigDecimal("30470"), new BigDecimal("1525"), new BigDecimal("5.27"),
                now.minusMinutes(minutesAgo), type, revealedAt);
    }

    private List<OutcomePatternRow> liveRows(int newsCount) {
        List<OutcomePatternRow> rows = new ArrayList<>();
        for (int i = 0; i < newsCount; i++) {
            rows.add(row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), i, LearningSession.SessionType.live, null));
        }
        return rows;
    }

    @Test
    void belowThreeDistinctNewsReturnsCountWithoutCases() {
        UUID news = UUID.randomUUID();
        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(List.of(
                row(UUID.randomUUID(), UUID.randomUUID(), news, 1, LearningSession.SessionType.live, null),
                row(UUID.randomUUID(), UUID.randomUUID(), news, 2, LearningSession.SessionType.live, null),
                row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, LearningSession.SessionType.live, null)));

        OutcomePatternResponse response = service().outcomes(userId, "us_equity", 5);

        assertEquals(2, response.distinctNewsCount());
        assertEquals("insufficient", response.stage());
        assertTrue(response.cases().isEmpty());
        verify(repository, never()).findFinalReflections(any());
    }

    @Test
    void threeDistinctNewsIsInitialObservationAndFiveIsRepeatedTendency() {
        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(liveRows(3));
        OutcomePatternResponse three = service().outcomes(userId, "us_equity", 5);
        assertEquals("initial_observation", three.stage());
        assertEquals(3, three.cases().size());

        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(liveRows(5));
        OutcomePatternResponse five = service().outcomes(userId, "us_equity", 5);
        assertEquals("repeated_tendency", five.stage());
        assertEquals(5, five.distinctNewsCount());
    }

    @Test
    void sameNewsCountsOnceAndKeepsMostRecentSessionFirstTarget() {
        UUID news = UUID.randomUUID();
        UUID recentSession = UUID.randomUUID();
        List<OutcomePatternRow> rows = new ArrayList<>(List.of(
                row(recentSession, UUID.randomUUID(), news, 1, LearningSession.SessionType.live, null),
                row(recentSession, UUID.randomUUID(), news, 1, LearningSession.SessionType.live, null),
                row(UUID.randomUUID(), UUID.randomUUID(), news, 9, LearningSession.SessionType.live, null)));
        rows.addAll(liveRows(2));
        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(rows);

        OutcomePatternResponse response = service().outcomes(userId, "us_equity", 5);

        assertEquals(3, response.distinctNewsCount());
        assertEquals(recentSession, response.cases().getFirst().sessionId());
    }

    @Test
    void unrevealedReplayIsExcluded() {
        List<OutcomePatternRow> rows = new ArrayList<>(liveRows(2));
        rows.add(row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, LearningSession.SessionType.replay, null));
        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(rows);
        assertEquals(2, service().outcomes(userId, "us_equity", 5).distinctNewsCount());

        rows.set(2, row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, LearningSession.SessionType.replay, now));
        assertEquals(3, service().outcomes(userId, "us_equity", 5).distinctNewsCount());
    }

    @Test
    void reflectionPrefersLearnedThenTargetSpecificAndIgnoresOtherTargets() {
        List<OutcomePatternRow> rows = liveRows(3);
        OutcomePatternRow first = rows.getFirst();
        when(repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5)).thenReturn(rows);
        when(repository.findFinalReflections(any())).thenReturn(List.of(
                new OutcomeReflectionRow(first.sessionId(), null, ReflectionNote.PromptKey.next_check, "다음에 확인"),
                new OutcomeReflectionRow(first.sessionId(), UUID.randomUUID(), ReflectionNote.PromptKey.learned, "다른 대상"),
                new OutcomeReflectionRow(first.sessionId(), null, ReflectionNote.PromptKey.learned, "세션 전체 배운 점"),
                new OutcomeReflectionRow(first.sessionId(), first.sessionTargetId(), ReflectionNote.PromptKey.learned, "이 대상 배운 점")));

        OutcomePatternResponse response = service().outcomes(userId, "us_equity", 5);

        assertEquals("이 대상 배운 점", response.cases().getFirst().reflection());
        assertNull(response.cases().get(1).reflection());
        assertEquals("up", response.cases().getFirst().direction());
    }

    @Test
    void invalidMarketOrHorizonIsBadRequest() {
        assertThrows(ApiException.class, () -> service().outcomes(userId, null, 5));
        assertThrows(ApiException.class, () -> service().outcomes(userId, " ", 5));
        assertThrows(ApiException.class, () -> service().outcomes(userId, "us_equity", 2));
        assertThrows(ApiException.class, () -> service().outcomes(userId, "us_equity", null));
        verifyNoInteractions(repository);
    }

    @Test
    void controllerUsesAuthenticatedUser() {
        CurrentUser currentUser = mock(CurrentUser.class);
        OutcomePatternService service = mock(OutcomePatternService.class);
        var response = new OutcomePatternResponse("rate", 1, 0, 3, 5, "insufficient", List.of());
        when(currentUser.id()).thenReturn(userId);
        when(service.outcomes(userId, "rate", 1)).thenReturn(response);
        assertSame(response, new OutcomePatternController(currentUser, service).outcomes("rate", 1));
    }
}
