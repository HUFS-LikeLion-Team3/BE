
package com.finsight.news;

import com.finsight.feedback.repository.FeedbackSourceDocumentRepository;
import com.finsight.global.exception.ApiException;
import com.finsight.news.entity.News;
import com.finsight.news.repository.*;
import com.finsight.news.service.NewsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayNewsFilterTest {

    @Mock
    private NewsRepository newsRepository;

    @Mock
    private UserInterestRepository interestRepository;

    @Mock
    private NewsFactRepository factRepository;

    @Mock
    private FeedbackSourceDocumentRepository documentRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private NewsTargetCandidateRepository candidateRepository;

    @Mock
    private TypedQuery<News> typedQuery;

    private NewsService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new NewsService(
                newsRepository,
                interestRepository,
                factRepository,
                documentRepository,
                entityManager,
                candidateRepository
        );

        userId = UUID.randomUUID();
    }

    private void mockEmptyQuery() {
        when(entityManager.createQuery(anyString(), eq(News.class)))
                .thenReturn(typedQuery);

        when(typedQuery.setParameter(anyString(), any()))
                .thenReturn(typedQuery);

        when(typedQuery.setFirstResult(anyInt()))
                .thenReturn(typedQuery);

        when(typedQuery.setMaxResults(anyInt()))
                .thenReturn(typedQuery);

        when(typedQuery.getResultList())
                .thenReturn(List.of());
    }

    @Test
    void replayListWithoutStatusPreservesExistingContract() {
        mockEmptyQuery();

        var result = service.list(
                userId,
                null,
                "all",
                "replay",
                null,
                "latest",
                0,
                20
        );

        verify(entityManager).createQuery(
                argThat(query ->
                        query.contains(
                                "n.contentType = :contentType"
                        )
                                && !query.contains("n.replayStatus")
                ),
                eq(News.class)
        );

        verify(typedQuery).setParameter(
                "contentType",
                "replay"
        );

        assertTrue(result.content().isEmpty());
    }

    @Test
    void filtersReplayNewsByDateAndMarketTarget() {
        mockEmptyQuery();

        UUID marketTargetId = UUID.randomUUID();

        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        service.list(
                userId,
                "semiconductor",
                "all",
                "replay",
                null,
                "latest",
                0,
                20,
                from,
                to,
                marketTargetId
        );

        verify(entityManager).createQuery(
                argThat(query ->
                        query.contains(
                                "n.publishedAt >= :fromInclusive"
                        )
                                && query.contains(
                                "n.publishedAt < :toExclusive"
                        )
                                && query.contains(
                                "c.target.id = :marketTargetId"
                        )
                ),
                eq(News.class)
        );

        ZoneId zone = ZoneId.of("Asia/Seoul");

        verify(typedQuery).setParameter(
                "fromInclusive",
                from.atStartOfDay(zone).toInstant()
        );

        verify(typedQuery).setParameter(
                "toExclusive",
                to.plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
        );

        verify(typedQuery).setParameter(
                "marketTargetId",
                marketTargetId
        );

        verify(typedQuery).setParameter(
                "category",
                "semiconductor"
        );
    }

    @Test
    void rejectsReversedDateRange() {
        assertThrows(
                ApiException.class,
                () -> service.list(
                        userId,
                        null,
                        "all",
                        "replay",
                        null,
                        "latest",
                        0,
                        20,
                        LocalDate.of(2026, 6, 30),
                        LocalDate.of(2026, 1, 1),
                        null
                )
        );

        verifyNoInteractions(entityManager);
    }

    @Test
    void oldNewsListKeepsLiveDefault() {
        mockEmptyQuery();

        var result = service.list(
                userId,
                null,
                null,
                null,
                null,
                "latest",
                0,
                20
        );

        verify(typedQuery).setParameter(
                "contentType",
                "live"
        );

        assertEquals(0, result.page());
        assertEquals(20, result.size());
        assertFalse(result.hasNext());
    }

    @Test
    void explicitReplayStatusIsPreserved() {
        mockEmptyQuery();

        service.list(
                userId,
                null,
                "all",
                "replay",
                "featured",
                "latest",
                0,
                20
        );

        verify(entityManager).createQuery(
                contains("n.replayStatus = :replayStatus"),
                eq(News.class)
        );

        verify(typedQuery).setParameter(
                "replayStatus",
                "featured"
        );
    }
}
