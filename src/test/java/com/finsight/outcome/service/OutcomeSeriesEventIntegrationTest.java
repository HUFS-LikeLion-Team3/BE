package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionStatusService;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.OutcomeCollectionEvent;
import com.finsight.outcome.entity.OutcomeSeriesPoint;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.OutcomeCollectionEventRepository;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        OutcomeCollectionService.class,
        LearningSessionStatusService.class
})
@EnabledIfEnvironmentVariable(
        named = "RUN_MYSQL_INTEGRATION_TESTS",
        matches = "true"
)
class OutcomeSeriesEventIntegrationTest {

    private static final LocalDate BASELINE_DATE =
            LocalDate.of(2026, 10, 9);

    private static final LocalDate BASELINE_DEADLINE =
            LocalDate.of(2026, 10, 23);

    private static final OffsetDateTime SUBMITTED_AT =
            OffsetDateTime.parse("2026-10-08T10:00:00+09:00");

    private static final ZoneId NEW_YORK =
            ZoneId.of("America/New_York");

    private static final Map<Integer, LocalDate> EXPECTED_DATES =
            Map.of(
                    1, LocalDate.of(2026, 10, 12),
                    5, LocalDate.of(2026, 10, 16),
                    20, LocalDate.of(2026, 11, 6)
            );

    private static final Map<Integer, LocalDate> DEADLINE_DATES =
            Map.of(
                    1, LocalDate.of(2026, 10, 26),
                    5, LocalDate.of(2026, 10, 30),
                    20, LocalDate.of(2026, 11, 20)
            );

    @Autowired
    private LearningSessionRepository sessionRepository;

    @Autowired
    private SessionTargetRepository sessionTargetRepository;

    @Autowired
    private SessionTargetBaselineRepository baselineRepository;

    @Autowired
    private MarketObservationRepository observationRepository;

    @Autowired
    private OutcomeSeriesPointRepository seriesPointRepository;

    @Autowired
    private OutcomeCollectionEventRepository eventRepository;

    @Autowired
    private OutcomeCollectionService collectionService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MarketDataService marketDataService;

    @MockitoBean
    private MarketTargetRepository marketTargetRepository;

    private UUID sessionId;
    private UUID sessionTargetId;
    private UUID marketTargetId;

    private MarketTarget marketTarget;

    @BeforeEach
    void setUp() {
        String database = jdbcTemplate.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        assertEquals(
                "finsight_test",
                database,
                "Integration tests must use finsight_test"
        );

        LearningSession session = LearningSession.create(
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        session.markSubmitted(SUBMITTED_AT);
        sessionRepository.saveAndFlush(session);

        sessionId = session.getId();
        marketTargetId = UUID.randomUUID();

        SessionTarget selectedTarget = SessionTarget.create(
                sessionId,
                marketTargetId,
                1
        );

        sessionTargetRepository.saveAndFlush(selectedTarget);
        sessionTargetId = selectedTarget.getId();

        marketTarget = mock(MarketTarget.class);

        when(marketTarget.getUnit()).thenReturn("USD");

        when(marketTargetRepository.findById(marketTargetId))
                .thenReturn(Optional.of(marketTarget));
    }

    @Test
    void readyResultsSaveFourSeriesPointsWithoutDuplicates() {
        prepareBaseline();

        stubClose(BASELINE_DATE, "100");

        assertEquals(
                MarketDataProvider.Status.ready,
                collectionService.collectBaseline(
                        sessionId,
                        sessionTargetId,
                        BASELINE_DATE
                )
        );

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                EXPECTED_DATES,
                DEADLINE_DATES
        );

        collectionService.collectBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE
        );

        stubClose(EXPECTED_DATES.get(1), "110");
        stubClose(EXPECTED_DATES.get(5), "95");
        stubClose(EXPECTED_DATES.get(20), "120");

        assertEquals(
                MarketDataProvider.Status.ready,
                collectionService.collectObservation(
                        sessionId,
                        sessionTargetId,
                        1,
                        EXPECTED_DATES.get(1),
                        0
                )
        );

        collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                EXPECTED_DATES.get(1),
                0
        );

        assertEquals(
                MarketDataProvider.Status.ready,
                collectionService.collectObservation(
                        sessionId,
                        sessionTargetId,
                        5,
                        EXPECTED_DATES.get(5),
                        0
                )
        );

        assertEquals(
                MarketDataProvider.Status.ready,
                collectionService.collectObservation(
                        sessionId,
                        sessionTargetId,
                        20,
                        EXPECTED_DATES.get(20),
                        0
                )
        );

        flushAndClear();

        List<OutcomeSeriesPoint> points =
                seriesPointRepository
                        .findAllBySessionTargetIdOrderByTradingDateAsc(
                                sessionTargetId
                        );

        assertEquals(4, points.size());

        assertEquals(
                List.of(
                        BASELINE_DATE,
                        EXPECTED_DATES.get(1),
                        EXPECTED_DATES.get(5),
                        EXPECTED_DATES.get(20)
                ),
                points.stream()
                        .map(OutcomeSeriesPoint::getTradingDate)
                        .toList()
        );

        assertValue(points.get(0).getCloseValue(), "100");
        assertValue(points.get(1).getCloseValue(), "110");
        assertValue(points.get(2).getCloseValue(), "95");
        assertValue(points.get(3).getCloseValue(), "120");

        for (OutcomeSeriesPoint point : points) {
            assertEquals("USD", point.getUnit());
            assertEquals(
                    "Integration Test Provider",
                    point.getDataSourceName()
            );
            assertEquals(
                    "https://example.com/test-market-data",
                    point.getDataSourceUrl()
            );
        }

        assertTrue(
                eventRepository
                        .findAllBySessionTargetIdOrderByOccurredAtAscIdAsc(
                                sessionTargetId
                        )
                        .isEmpty()
        );

        assertEquals(
                LearningSession.LearningStatus.reflection_pending,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void expiredBaselineSavesOneDataMissingEvent() {
        prepareBaseline();

        collectionService.expireBaseline(
                sessionId,
                sessionTargetId,
                "BASELINE_DEADLINE_EXCEEDED"
        );

        flushAndClear();

        assertTrue(
                seriesPointRepository
                        .findAllBySessionTargetIdOrderByTradingDateAsc(
                                sessionTargetId
                        )
                        .isEmpty()
        );

        List<OutcomeCollectionEvent> events =
                eventRepository
                        .findAllBySessionTargetIdOrderByOccurredAtAscIdAsc(
                                sessionTargetId
                        );

        assertEquals(1, events.size());

        OutcomeCollectionEvent event = events.get(0);

        assertEquals(
                sessionTargetId,
                event.getSessionTargetId()
        );

        assertNull(event.getHorizon());

        assertEquals(
                OutcomeCollectionEvent.OutcomeCollectionEventType.data_missing,
                event.getEventType()
        );

        assertEquals(
                "BASELINE_DEADLINE_EXCEEDED",
                event.getDetail()
        );

        assertNotNull(event.getOccurredAt());
        assertNotNull(event.getCreatedAt());

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        assertEquals(3, observations.size());

        assertTrue(
                observations.stream().allMatch(
                        observation ->
                                observation.getStatus()
                                        == MarketObservation.OutcomeStatus.unavailable
                )
        );
    }

    @Test
    void expiredObservationSavesOneHorizonEvent() {
        prepareReadyBaselineAndObservations();

        collectionService.expireObservation(
                sessionId,
                sessionTargetId,
                5,
                MarketObservation.OutcomeExceptionStatus.data_missing,
                "OBSERVATION_DEADLINE_EXCEEDED"
        );

        collectionService.expireObservation(
                sessionId,
                sessionTargetId,
                5,
                MarketObservation.OutcomeExceptionStatus.data_missing,
                "OBSERVATION_DEADLINE_EXCEEDED"
        );

        flushAndClear();

        List<OutcomeCollectionEvent> events =
                eventRepository
                        .findAllBySessionTargetIdOrderByOccurredAtAscIdAsc(
                                sessionTargetId
                        );

        assertEquals(1, events.size());

        OutcomeCollectionEvent event = events.get(0);

        assertEquals(5, event.getHorizon());

        assertEquals(
                OutcomeCollectionEvent.OutcomeCollectionEventType.data_missing,
                event.getEventType()
        );

        assertEquals(
                "OBSERVATION_DEADLINE_EXCEEDED",
                event.getDetail()
        );

        MarketObservation t5 =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        )
                        .stream()
                        .filter(observation ->
                                observation.getHorizon() == 5
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                MarketObservation.OutcomeStatus.unavailable,
                t5.getStatus()
        );

        List<OutcomeSeriesPoint> points =
                seriesPointRepository
                        .findAllBySessionTargetIdOrderByTradingDateAsc(
                                sessionTargetId
                        );

        assertEquals(1, points.size());
        assertEquals(
                BASELINE_DATE,
                points.get(0).getTradingDate()
        );
    }

    private void prepareBaseline() {
        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );
    }

    private void prepareReadyBaselineAndObservations() {
        prepareBaseline();

        stubClose(BASELINE_DATE, "100");

        assertEquals(
                MarketDataProvider.Status.ready,
                collectionService.collectBaseline(
                        sessionId,
                        sessionTargetId,
                        BASELINE_DATE
                )
        );

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                EXPECTED_DATES,
                DEADLINE_DATES
        );
    }

    private void stubClose(LocalDate date, String value) {
        MarketDataService.Close close =
                createClose(date, value);

        when(marketDataService.regularCloseOn(
                marketTarget,
                date
        )).thenReturn(
                new MarketDataService.Result<>(
                        MarketDataProvider.Status.ready,
                        close
                )
        );
    }

    private MarketDataService.Close createClose(
            LocalDate date,
            String value
    ) {
        OffsetDateTime asOfAt = date
                .atTime(LocalTime.of(16, 0))
                .atZone(NEW_YORK)
                .toOffsetDateTime();

        return new MarketDataService.Close(
                date,
                asOfAt,
                new BigDecimal(value),
                "USD",
                "Integration Test Provider",
                "https://example.com/test-market-data"
        );
    }

    private void assertValue(
            BigDecimal actual,
            String expected
    ) {
        assertNotNull(actual);

        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual)
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}