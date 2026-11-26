package com.finsight.pattern;

import com.finsight.learning.entity.LearningSession;
import com.finsight.market.entity.MarketTarget;
import com.finsight.news.entity.News;
import com.finsight.outcome.entity.MarketObservation;
import com.finsight.outcome.entity.SessionTargetBaseline;
import com.finsight.pattern.dto.OutcomePatternRow;
import com.finsight.pattern.repository.OutcomePatternRepository;
import com.finsight.prediction.entity.Prediction;
import com.finsight.prediction.entity.SessionTarget;
import com.finsight.reflection.entity.ReflectionNote;
import com.finsight.reflection.entity.ReflectionSubmission;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:finsight_outcome_pattern;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Transactional
class OutcomePatternRepositoryQueryTest {
    @Autowired OutcomePatternRepository repository;
    @Autowired EntityManager em;

    private final OffsetDateTime submitted = OffsetDateTime.parse("2026-09-16T18:10:00Z");

    @Test
    void joinsReadyObservationWithPredictionBaselineNewsAndFinalReflection() {
        UUID userId = UUID.randomUUID();
        MarketTarget nasdaq = market("NASDAQ100-IT", "us_equity", "point");
        MarketTarget yield = market("US10Y-IT", "rate", "%");
        News news = news("연준, 기준금리 0.25%p 인상");

        LearningSession session = LearningSession.create(userId, news.getId());
        session.markSubmitted(submitted);
        em.persist(session);
        SessionTarget nasdaqTarget = target(session, nasdaq, 0, 5, Prediction.Direction.down, "금리 인상은 부담");
        target(session, yield, 1, 5, Prediction.Direction.up, "금리 상승");

        SessionTargetBaseline baseline = ready(nasdaqTarget, 5, "28945", "30470");
        // T1 관측은 pending으로 남겨 두어 T1 조회에서는 빠져야 한다.
        em.persist(MarketObservation.createPending(nasdaqTarget.getId(), baseline.getId(), 1, submitted,
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), "point"));

        ReflectionSubmission submission = ReflectionSubmission.create(session.getId(), UUID.randomUUID(), "hash", "final");
        em.persist(submission);
        em.persist(ReflectionNote.create(session.getId(), null, "final",
                ReflectionNote.PromptKey.learned, "인상인데도 지수가 올랐다", submission, submitted.plusDays(30)));
        em.flush();

        List<OutcomePatternRow> rows = repository.findReadyOutcomes(userId, "us_equity", (short) 5, 5);
        assertEquals(1, rows.size());
        OutcomePatternRow row = rows.getFirst();
        assertEquals("연준, 기준금리 0.25%p 인상", row.newsTitle());
        assertEquals("NASDAQ100-IT", row.targetCode());
        assertEquals(Prediction.Direction.down, row.direction());
        assertEquals(0, new BigDecimal("28945").compareTo(row.baselineValue()));
        assertEquals(0, new BigDecimal("30470").compareTo(row.observedValue()));
        assertEquals(LocalDate.of(2026, 9, 23), row.observationDate());

        assertTrue(repository.findReadyOutcomes(userId, "us_equity", (short) 1, 1).isEmpty());
        assertTrue(repository.findReadyOutcomes(userId, "rate", (short) 5, 5).isEmpty());
        assertTrue(repository.findReadyOutcomes(UUID.randomUUID(), "us_equity", (short) 5, 5).isEmpty());

        var notes = repository.findFinalReflections(List.of(session.getId()));
        assertEquals(1, notes.size());
        assertEquals("인상인데도 지수가 올랐다", notes.getFirst().body());
    }

    private MarketTarget market(String code, String category, String unit) {
        MarketTarget target = new MarketTarget();
        ReflectionTestUtils.setField(target, "code", code);
        ReflectionTestUtils.setField(target, "displayName", code);
        ReflectionTestUtils.setField(target, "marketCategory", category);
        ReflectionTestUtils.setField(target, "targetType", category.equals("rate") ? "yield" : "index");
        ReflectionTestUtils.setField(target, "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "큰 방향성 없음"));
        ReflectionTestUtils.setField(target, "unit", unit);
        ReflectionTestUtils.setField(target, "calendarCode", "FRED");
        ReflectionTestUtils.setField(target, "timezone", "America/New_York");
        ReflectionTestUtils.setField(target, "dataSymbol", code);
        em.persist(target);
        return target;
    }

    private News news(String title) {
        News news = new News();
        ReflectionTestUtils.setField(news, "title", title);
        ReflectionTestUtils.setField(news, "category", "rate");
        ReflectionTestUtils.setField(news, "briefing", "테스트");
        ReflectionTestUtils.setField(news, "status", "published");
        ReflectionTestUtils.setField(news, "contentType", "live");
        ReflectionTestUtils.setField(news, "replayStatus", "not_eligible");
        ReflectionTestUtils.setField(news, "publishedAt", Instant.parse("2026-09-16T18:00:00Z"));
        ReflectionTestUtils.setField(news, "referenceAt", Instant.parse("2026-09-16T18:05:00Z"));
        em.persist(news);
        return news;
    }

    private SessionTarget target(LearningSession session, MarketTarget market, int order, int horizon,
                                 Prediction.Direction direction, String reason) {
        SessionTarget target = SessionTarget.create(session.getId(), market.getId(), order);
        em.persist(target);
        Prediction prediction = Prediction.create(target.getId(), horizon);
        prediction.update(true, direction, true, reason);
        prediction.markSubmitted(submitted);
        em.persist(prediction);
        return target;
    }

    private SessionTargetBaseline ready(SessionTarget target, int horizon, String base, String observed) {
        SessionTargetBaseline baseline = SessionTargetBaseline.createPending(
                target.getId(), LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18), "point");
        baseline.markReady(LocalDate.of(2026, 9, 16), submitted, new BigDecimal(base), "FRED", "https://fred.stlouisfed.org");
        em.persist(baseline);
        MarketObservation observation = MarketObservation.createPending(target.getId(), baseline.getId(), horizon,
                submitted, LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 25), "point");
        observation.markReady(LocalDate.of(2026, 9, 23), submitted.plusDays(7), new BigDecimal(observed),
                new BigDecimal(base), 0, "FRED", "https://fred.stlouisfed.org");
        em.persist(observation);
        return baseline;
    }
}
