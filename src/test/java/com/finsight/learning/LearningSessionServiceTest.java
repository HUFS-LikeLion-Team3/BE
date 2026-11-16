package com.finsight.learning;

import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionService;
import com.finsight.news.entity.News;
import com.finsight.news.repository.NewsRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningSessionServiceTest {
    @Mock LearningSessionRepository sessions;
    @Mock UserRepository users;
    @Mock NewsRepository news;

    LearningSessionService service;
    final UUID owner = UUID.randomUUID();
    final UUID newsId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new LearningSessionService(sessions, users, news);
    }

    private void authenticatedUser() {
        when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(mock(User.class)));
    }

    private News publishedLiveNews() {
        News item = mock(News.class);
        when(item.getContentType()).thenReturn("live");
        when(news.findByIdAndStatus(newsId, "published")).thenReturn(Optional.of(item));
        return item;
    }

    @Test
    void createsOnlyOnceForPublishedLiveNewsAndLocksUserFirst() {
        authenticatedUser();
        publishedLiveNews();
        when(sessions.findByUserIdAndNewsId(owner, newsId)).thenReturn(Optional.empty());
        when(sessions.saveAndFlush(any(LearningSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createOrGet(owner, newsId, "live");

        assertTrue(result.created());
        assertEquals(owner, result.session().getUserId());
        assertEquals(newsId, result.session().getNewsId());
        assertEquals(LearningSession.LearningStatus.drafting, result.session().getStatus());
        InOrder calls = inOrder(users, sessions, news);
        calls.verify(users).findByIdForUpdate(owner);
        calls.verify(sessions).findByUserIdAndNewsId(owner, newsId);
        calls.verify(news).findByIdAndStatus(newsId, "published");
        calls.verify(sessions).saveAndFlush(any(LearningSession.class));
    }

    @Test
    void existingSubmittedSessionIsReturnedWithoutChangingItOrRecheckingArchivedNews() {
        authenticatedUser();
        LearningSession existing = LearningSession.create(owner, newsId);
        OffsetDateTime submitted = OffsetDateTime.parse("2026-10-05T10:00:00+09:00");
        existing.markSubmitted(submitted);
        when(sessions.findByUserIdAndNewsId(owner, newsId)).thenReturn(Optional.of(existing));

        var result = service.createOrGet(owner, newsId, "live");

        assertFalse(result.created());
        assertSame(existing, result.session());
        assertEquals(submitted, existing.getSubmittedAt());
        assertEquals(LearningSession.LearningStatus.baseline_pending, existing.getStatus());
        verifyNoInteractions(news);
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void replayIsRejectedEvenWhenAnExistingSessionCouldBeFound() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createOrGet(owner, newsId, "replay"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verifyNoInteractions(users, sessions, news);
    }

    @Test
    void differentExistingSessionTypeConflictsWithoutMutation() {
        authenticatedUser();
        LearningSession existing = LearningSession.create(owner, newsId);
        ReflectionTestUtils.setField(existing, "sessionType", LearningSession.SessionType.replay);
        when(sessions.findByUserIdAndNewsId(owner, newsId)).thenReturn(Optional.of(existing));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createOrGet(owner, newsId, "live"));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verifyNoInteractions(news);
    }

    @Test
    void noPublishedNewsReturns404AndDoesNotInsert() {
        authenticatedUser();
        when(sessions.findByUserIdAndNewsId(owner, newsId)).thenReturn(Optional.empty());
        when(news.findByIdAndStatus(newsId, "published")).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class,
                () -> service.createOrGet(owner, newsId, "live"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void publishedReplayNewsIsNotEligibleForP0LiveSession() {
        authenticatedUser();
        when(sessions.findByUserIdAndNewsId(owner, newsId)).thenReturn(Optional.empty());
        News replayNews = mock(News.class);
        when(replayNews.getContentType()).thenReturn("replay");
        when(news.findByIdAndStatus(newsId, "published")).thenReturn(Optional.of(replayNews));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createOrGet(owner, newsId, "live"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void absentUserIsUnauthorizedWithoutQueryingSessionOrNews() {
        when(users.findByIdForUpdate(owner)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class,
                () -> service.createOrGet(owner, newsId, "live"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        verifyNoInteractions(sessions, news);
    }

    @Test
    void listingDelegatesAllFiltersAndOnlyUsesAuthenticatedOwner() {
        var pageable = PageRequest.of(0, 20);
        var session = LearningSession.create(owner, newsId);
        when(sessions.findByCondition(eq(owner), eq(newsId),
                eq(LearningSession.LearningStatus.completed), eq(LearningSession.SessionType.live), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(session), pageable, 1));

        var result = service.findAll(owner, newsId,
                LearningSession.LearningStatus.completed, LearningSession.SessionType.live, pageable);
        assertEquals(1, result.getTotalElements());
        assertEquals(newsId, result.getContent().getFirst().newsId());
    }

    @Test
    void detailRejectsOtherOwnersAndMissingSessions() {
        var sessionId = UUID.randomUUID();
        var otherOwner = UUID.randomUUID();
        when(sessions.findById(sessionId)).thenReturn(Optional.empty(),
                Optional.of(LearningSession.create(otherOwner, newsId)));
        ApiException missing = assertThrows(ApiException.class,
                () -> service.findById(owner, sessionId));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        ApiException forbidden = assertThrows(ApiException.class,
                () -> service.findById(owner, sessionId));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
    }

    @Test
    void timestampsAreSerializedWithKoreanOffsetWithoutChangingInstant() {
        var session = LearningSession.create(owner, newsId);
        OffsetDateTime utc = OffsetDateTime.of(2026, 10, 5, 1, 0, 0, 0, ZoneOffset.UTC);
        ReflectionTestUtils.setField(session, "createdAt", utc);
        var detail = com.finsight.learning.dto.LearningSessionDetailResponse.from(session);
        assertEquals(ZoneOffset.ofHours(9), detail.createdAt().getOffset());
        assertEquals(utc.toInstant(), detail.createdAt().toInstant());
        assertNull(detail.savedAt());
    }

    @Test
    void databaseUniqueConstraintAndCrossRequestLockAreDeclared() throws Exception {
        Table table = LearningSession.class.getAnnotation(Table.class);
        assertTrue(java.util.Arrays.stream(table.uniqueConstraints()).anyMatch(c ->
                java.util.Arrays.equals(c.columnNames(), new String[]{"user_id", "news_id"})));
        Method method = UserRepository.class.getMethod("findByIdForUpdate", UUID.class);
        assertEquals(LockModeType.PESSIMISTIC_WRITE,
                method.getAnnotation(org.springframework.data.jpa.repository.Lock.class).value());
    }
}
