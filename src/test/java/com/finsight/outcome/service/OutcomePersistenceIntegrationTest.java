package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.learning.service.LearningSessionStatusService;
import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.MarketObservationRepository;
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
class OutcomePersistenceIntegrationTest {

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
    void baselineReadyCreatesThreePendingObservations() {
        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

        stubClose(BASELINE_DATE, "100");

        MarketDataProvider.Status status =
                collectionService.collectBaseline(
                        sessionId,
                        sessionTargetId,
                        BASELINE_DATE
                );

        assertEquals(MarketDataProvider.Status.ready, status);

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                EXPECTED_DATES,
                DEADLINE_DATES
        );

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                EXPECTED_DATES,
                DEADLINE_DATES
        );

        flushAndClear();

        SessionTargetBaseline baseline = baselineRepository
                .findBySessionTargetId(sessionTargetId)
                .orElseThrow();

        assertEquals(
                SessionTargetBaseline.BaselineStatus.ready,
                baseline.getStatus()
        );

        assertEquals(
                BASELINE_DATE,
                baseline.getBaselineSessionDate()
        );

        assertEquals(
                0,
                new BigDecimal("100")
                        .compareTo(baseline.getBaselineValue())
        );

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        assertEquals(3, observations.size());

        assertEquals(
                List.of(1, 5, 20),
                observations.stream()
                        .map(MarketObservation::getHorizon)
                        .toList()
        );

        for (MarketObservation observation : observations) {
            assertEquals(
                    MarketObservation.OutcomeStatus.pending,
                    observation.getStatus()
            );

            assertEquals(
                    baseline.getId(),
                    observation.getBaselineId()
            );

            assertFalse(observation.isUsableForAiEvidence());
        }

        LearningSession savedSession = sessionRepository
                .findById(sessionId)
                .orElseThrow();

        assertEquals(
                LearningSession.LearningStatus.observing,
                savedSession.getStatus()
        );
    }

    @Test
    void allReadyObservationsTransitionSessionToReflectionPending() {
        prepareReadyBaselineAndObservations();

        stubClose(EXPECTED_DATES.get(1), "110");
        stubClose(EXPECTED_DATES.get(5), "95");
        stubClose(EXPECTED_DATES.get(20), "120");

        collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                1,
                EXPECTED_DATES.get(1),
                0
        );

        collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                5,
                EXPECTED_DATES.get(5),
                0
        );

        flushAndClear();

        assertEquals(
                LearningSession.LearningStatus.observing,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );

        collectionService.collectObservation(
                sessionId,
                sessionTargetId,
                20,
                EXPECTED_DATES.get(20),
                0
        );

        flushAndClear();

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        assertEquals(3, observations.size());

        assertTrue(observations.stream().allMatch(
                observation -> observation.getStatus()
                        == MarketObservation.OutcomeStatus.ready
        ));

        assertTrue(observations.stream().allMatch(
                MarketObservation::isUsableForAiEvidence
        ));

        MarketObservation t1 = observations.get(0);

        assertEquals(1, t1.getHorizon());

        assertEquals(
                0,
                new BigDecimal("10")
                        .compareTo(t1.getChangeValue())
        );

        assertEquals(
                0,
                new BigDecimal("10")
                        .compareTo(t1.getChangePercent())
        );

        MarketObservation t5 = observations.get(1);

        assertEquals(
                0,
                new BigDecimal("-5")
                        .compareTo(t5.getChangeValue())
        );

        assertEquals(
                0,
                new BigDecimal("-5")
                        .compareTo(t5.getChangePercent())
        );

        MarketObservation t20 = observations.get(2);

        assertEquals(
                0,
                new BigDecimal("20")
                        .compareTo(t20.getChangeValue())
        );

        assertEquals(
                LearningSession.LearningStatus.reflection_pending,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void unavailableBaselineClosesAllObservationsTogether() {
        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

        collectionService.expireBaseline(
                sessionId,
                sessionTargetId,
                "BASELINE_DEADLINE_EXCEEDED"
        );

        flushAndClear();

        SessionTargetBaseline baseline = baselineRepository
                .findBySessionTargetId(sessionTargetId)
                .orElseThrow();

        assertEquals(
                SessionTargetBaseline.BaselineStatus.unavailable,
                baseline.getStatus()
        );

        assertNull(baseline.getBaselineValue());

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        assertEquals(3, observations.size());

        for (MarketObservation observation : observations) {
            assertEquals(
                    MarketObservation.OutcomeStatus.unavailable,
                    observation.getStatus()
            );

            assertNull(observation.getExpectedObservationDate());
            assertNull(observation.getObservationDeadlineDate());
            assertNull(observation.getObservationSessionDate());
            assertNull(observation.getObservedValue());
            assertNull(observation.getChangeValue());
            assertNull(observation.getChangePercent());

            assertEquals(
                    0,
                    observation.getObservationDelaySessions()
            );

            assertFalse(observation.isUsableForAiEvidence());
        }

        assertEquals(
                LearningSession.LearningStatus.reflection_pending,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    private void prepareReadyBaselineAndObservations() {
        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

        stubClose(BASELINE_DATE, "100");

        collectionService.collectBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE
        );

        collectionService.initializeObservations(
                sessionId,
                sessionTargetId,
                EXPECTED_DATES,
                DEADLINE_DATES
        );
    }

    private void stubClose(LocalDate date, String value) {
        OffsetDateTime closeAt = date.atTime(LocalTime.of(16, 0))
                .atZone(NEW_YORK)
                .toOffsetDateTime();

        MarketDataService.Close close = new MarketDataService.Close(
                date,
                closeAt,
                new BigDecimal(value),
                "USD",
                "Integration Test Provider",
                "https://example.com/test-market-data"
        );

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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}