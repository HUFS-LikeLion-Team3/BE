package com.finsight.outcome.service;

import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.news.entity.News;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.outcome.repository.OutcomeSeriesPointRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.prediction.repository.SessionTargetRepository;
import com.finsight.auth.entity.User;
import com.finsight.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/finsight_test?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.jpa.hibernate.ddl-auto=none",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "finsight.outcome.collection.enabled=true",
        "finsight.outcome.collection.daily-initial-delay-ms=3000",
        "finsight.outcome.collection.daily-fixed-delay-ms=600000",
        "finsight.outcome.collection.result-initial-delay-ms=600000",
        "finsight.outcome.collection.series-initial-delay-ms=600000"
})
@ActiveProfiles("market-data")
@DirtiesContext
@EnabledIfEnvironmentVariable(
        named = "RUN_DAILY_LIVE_IT",
        matches = "true"
)
class DailyOutcomeLiveSchedulerIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private LearningSessionRepository sessions;
    @Autowired private SessionTargetRepository targets;
    @Autowired private MarketTargetRepository markets;
    @Autowired private SessionTargetBaselineRepository baselines;
    @Autowired private OutcomeSeriesPointRepository series;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID userId;
    private UUID newsId;
    private UUID sessionId;
    private UUID targetId;

    @Test
    void scheduledJobFetchesActualTreasuryValueAndCommitsToMySql()
            throws Exception {

        assertEquals(
                "finsight_test",
                jdbc.queryForObject("SELECT DATABASE()", String.class),
                "Refusing to run against a non-test database"
        );

        MarketTarget treasury = markets.findAll()
                .stream()
                .filter(m -> "USTY".equals(m.getCalendarCode()))
                .filter(m -> "BC_10YEAR".equals(m.getDataSymbol()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "US10Y target missing. Verify market-data profile seed."
                ));

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> {
                    User user = users.saveAndFlush(
                            new User(
                                    "kakao",
                                    UUID.randomUUID().toString(),
                                    "daily-live-it"
                            )
                    );
                    userId = user.getId();

                    News news = new News();
                    ReflectionTestUtils.setField(
                            news, "title", "DAILY live integration test"
                    );
                    ReflectionTestUtils.setField(
                            news, "category", "economy"
                    );
                    ReflectionTestUtils.setField(
                            news, "briefing", "Temporary test fixture"
                    );
                    ReflectionTestUtils.setField(
                            news, "status", "published"
                    );
                    ReflectionTestUtils.setField(
                            news, "contentType", "live"
                    );
                    ReflectionTestUtils.setField(
                            news, "replayStatus", "not_eligible"
                    );
                    ReflectionTestUtils.setField(
                            news, "publishedAt", Instant.now()
                    );
                    ReflectionTestUtils.setField(
                            news, "referenceAt", Instant.now()
                    );

                    em.persist(news);
                    em.flush();
                    newsId = news.getId();

                    LearningSession session =
                            LearningSession.create(userId, newsId);

                    session.markSubmitted(
                            OffsetDateTime.parse(
                                    "2026-10-08T16:00:00-04:00"
                            )
                    );
                    sessions.saveAndFlush(session);
                    sessionId = session.getId();

                    SessionTarget selected = SessionTarget.create(
                            sessionId,
                            treasury.getId(),
                            1
                    );

                    targets.saveAndFlush(selected);
                    targetId = selected.getId();
                });

        LocalDate expected = LocalDate.parse("2026-10-09");
        boolean saved = false;

        long deadline =
                System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);

        while (System.nanoTime() < deadline) {
            var baseline = baselines.findBySessionTargetId(targetId);
            var point = series.findBySessionTargetIdAndTradingDate(
                    targetId,
                    expected
            );

            if (baseline.isPresent()
                    && baseline.get().getStatus()
                    == SessionTargetBaseline.BaselineStatus.ready
                    && point.isPresent()) {

                assertEquals(
                        expected,
                        baseline.get().getBaselineSessionDate()
                );
                assertNotNull(baseline.get().getBaselineValue());
                assertTrue(
                        baseline.get().getBaselineValue().signum() > 0
                );
                assertEquals(
                        "%",
                        baseline.get().getUnit()
                );
                assertEquals(
                        0,
                        baseline.get().getBaselineValue()
                                .compareTo(point.get().getCloseValue())
                );

                saved = true;
                break;
            }

            Thread.sleep(500);
        }

        assertTrue(
                saved,
                "DAILY @Scheduled job did not persist the actual US10Y T0 "
                        + "and series point within 90 seconds"
        );
    }

    @AfterEach
    void cleanUp() {
        if (targetId == null) {
            return;
        }

        assertEquals(
                "finsight_test",
                jdbc.queryForObject("SELECT DATABASE()", String.class)
        );

        String t = targetId.toString();
        String s = sessionId.toString();
        String n = newsId.toString();
        String u = userId.toString();

        jdbc.update(
                "DELETE FROM outcome_collection_events "
                        + "WHERE session_target_id = UNHEX(REPLACE(?, '-', ''))",
                t
        );
        jdbc.update(
                "DELETE FROM outcome_series_points "
                        + "WHERE session_target_id = UNHEX(REPLACE(?, '-', ''))",
                t
        );
        jdbc.update(
                "DELETE FROM market_observations "
                        + "WHERE session_target_id = UNHEX(REPLACE(?, '-', ''))",
                t
        );
        jdbc.update(
                "DELETE FROM session_target_baselines "
                        + "WHERE session_target_id = UNHEX(REPLACE(?, '-', ''))",
                t
        );
        jdbc.update(
                "DELETE FROM session_targets "
                        + "WHERE id = UNHEX(REPLACE(?, '-', ''))",
                t
        );
        jdbc.update(
                "DELETE FROM learning_sessions "
                        + "WHERE id = UNHEX(REPLACE(?, '-', ''))",
                s
        );
        jdbc.update(
                "DELETE FROM news "
                        + "WHERE id = UNHEX(REPLACE(?, '-', ''))",
                n
        );
        jdbc.update(
                "DELETE FROM users "
                        + "WHERE id = UNHEX(REPLACE(?, '-', ''))",
                u
        );
    }
}