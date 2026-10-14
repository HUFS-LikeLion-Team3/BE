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

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(
        named = "RUN_MYSQL_INTEGRATION_TESTS",
        matches = "true"
)
class OutcomeCommittedTransactionIntegrationTest {

    private static final LocalDate BASELINE_DATE =
            LocalDate.of(2026, 10, 9);

    private static final LocalDate BASELINE_DEADLINE =
            LocalDate.of(2026, 10, 23);

    private static final OffsetDateTime SUBMITTED_AT =
            OffsetDateTime.parse("2026-10-08T10:00:00+09:00");

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
    private PlatformTransactionManager transactionManager;

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

        assertEquals("finsight_test", database);

        LearningSession session = LearningSession.create(
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        session.markSubmitted(SUBMITTED_AT);
        sessionRepository.saveAndFlush(session);

        sessionId = session.getId();
        marketTargetId = UUID.randomUUID();

        SessionTarget target = SessionTarget.create(
                sessionId,
                marketTargetId,
                1
        );

        sessionTargetRepository.saveAndFlush(target);

        sessionTargetId = target.getId();

        marketTarget = mock(MarketTarget.class);

        when(marketTarget.getUnit()).thenReturn("USD");

        when(marketTargetRepository.findById(marketTargetId))
                .thenReturn(Optional.of(marketTarget));
    }

    @AfterEach
    void cleanUp() {
        if (sessionTargetId == null) {
            return;
        }

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        observationRepository.deleteAllInBatch(observations);

        baselineRepository.findBySessionTargetId(sessionTargetId)
                .ifPresent(baselineRepository::delete);

        sessionTargetRepository.deleteById(sessionTargetId);
        sessionRepository.deleteById(sessionId);
    }

    @Test
    void committedResultsAreVisibleAcrossTransactions() {
        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

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

        assertEquals(
                LearningSession.LearningStatus.observing,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );

        stubClose(EXPECTED_DATES.get(1), "110");
        stubClose(EXPECTED_DATES.get(5), "95");
        stubClose(EXPECTED_DATES.get(20), "120");

        for (int horizon : List.of(1, 5, 20)) {
            MarketDataProvider.Status result =
                    collectionService.collectObservation(
                            sessionId,
                            sessionTargetId,
                            horizon,
                            EXPECTED_DATES.get(horizon),
                            0
                    );

            assertEquals(
                    MarketDataProvider.Status.ready,
                    result
            );
        }

        List<MarketObservation> observations =
                observationRepository
                        .findAllBySessionTargetIdOrderByHorizonAsc(
                                sessionTargetId
                        );

        assertEquals(3, observations.size());

        assertTrue(observations.stream().allMatch(
                observation ->
                        observation.getStatus()
                                == MarketObservation.OutcomeStatus.ready
        ));

        assertEquals(
                LearningSession.LearningStatus.reflection_pending,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void outerTransactionFailureRollsBackBaselineCreation() {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> transaction.executeWithoutResult(status -> {

                    collectionService.initializeBaseline(
                            sessionId,
                            sessionTargetId,
                            BASELINE_DATE,
                            BASELINE_DEADLINE
                    );

                    assertTrue(
                            baselineRepository
                                    .findBySessionTargetId(sessionTargetId)
                                    .isPresent()
                    );

                    throw new IllegalStateException(
                            "FORCED_ROLLBACK"
                    );
                })
        );

        assertTrue(
                baselineRepository
                        .findBySessionTargetId(sessionTargetId)
                        .isEmpty()
        );
    }

    @Test
    void concurrentBaselineCollectionDoesNotOverwriteResult()
            throws Exception {

        collectionService.initializeBaseline(
                sessionId,
                sessionTargetId,
                BASELINE_DATE,
                BASELINE_DEADLINE
        );

        MarketDataService.Close close =
                createClose(BASELINE_DATE, "100");

        CyclicBarrier barrier = new CyclicBarrier(2);

        when(marketDataService.regularCloseOn(
                marketTarget,
                BASELINE_DATE
        )).thenAnswer(invocation -> {

            barrier.await(10, TimeUnit.SECONDS);

            return new MarketDataService.Result<>(
                    MarketDataProvider.Status.ready,
                    close
            );
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MarketDataProvider.Status> first =
                    executor.submit(() ->
                            collectionService.collectBaseline(
                                    sessionId,
                                    sessionTargetId,
                                    BASELINE_DATE
                            )
                    );

            Future<MarketDataProvider.Status> second =
                    executor.submit(() ->
                            collectionService.collectBaseline(
                                    sessionId,
                                    sessionTargetId,
                                    BASELINE_DATE
                            )
                    );

            assertEquals(
                    MarketDataProvider.Status.ready,
                    first.get(20, TimeUnit.SECONDS)
            );

            assertEquals(
                    MarketDataProvider.Status.ready,
                    second.get(20, TimeUnit.SECONDS)
            );

        } finally {
            executor.shutdownNow();
        }

        SessionTargetBaseline baseline =
                baselineRepository
                        .findBySessionTargetId(sessionTargetId)
                        .orElseThrow();

        assertEquals(
                SessionTargetBaseline.BaselineStatus.ready,
                baseline.getStatus()
        );

        assertEquals(
                0,
                new BigDecimal("100")
                        .compareTo(baseline.getBaselineValue())
        );

        assertEquals(
                LearningSession.LearningStatus.observing,
                sessionRepository.findById(sessionId)
                        .orElseThrow()
                        .getStatus()
        );
    }

    private void stubClose(LocalDate date, String value) {
        when(marketDataService.regularCloseOn(
                marketTarget,
                date
        )).thenReturn(
                new MarketDataService.Result<>(
                        MarketDataProvider.Status.ready,
                        createClose(date, value)
                )
        );
    }

    private MarketDataService.Close createClose(
            LocalDate date,
            String value
    ) {
        OffsetDateTime asOfAt = date
                .atTime(16, 0)
                .atZone(java.time.ZoneId.of("America/New_York"))
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
}