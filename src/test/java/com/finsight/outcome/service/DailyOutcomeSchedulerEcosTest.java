package com.finsight.outcome.service;

import com.finsight.learning.repository.LearningSessionRepository;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.outcome.repository.MarketObservationRepository;
import com.finsight.outcome.repository.SessionTargetBaselineRepository;
import com.finsight.prediction.repository.SessionTargetRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyOutcomeSchedulerEcosTest {
    final ZoneId seoul = ZoneId.of("Asia/Seoul");
    final DailyOutcomeScheduler scheduler = new DailyOutcomeScheduler(
            mock(LearningSessionRepository.class), mock(SessionTargetRepository.class), mock(MarketTargetRepository.class),
            mock(SessionTargetBaselineRepository.class), mock(MarketObservationRepository.class),
            mock(MarketDataService.class), mock(OutcomeCollectionService.class));

    MarketTarget ecos() {
        MarketTarget market = mock(MarketTarget.class);
        when(market.getCalendarCode()).thenReturn("ECOS");
        when(market.getTimezone()).thenReturn("Asia/Seoul");
        return market;
    }

    OffsetDateTime at(String date, int hour) {
        return LocalDate.parse(date).atTime(hour, 0).atZone(seoul).toOffsetDateTime();
    }

    @Test void baselineIsFirstKrxCloseAfterSubmissionOnWeekdays() {
        var market = ecos();
        // 2026-11-23 is a Monday.
        assertEquals(LocalDate.parse("2026-11-23"), scheduler.firstDate(market, at("2026-11-23", 10)));
        assertEquals(LocalDate.parse("2026-11-24"), scheduler.firstDate(market, at("2026-11-23", 16)));
        assertEquals(LocalDate.parse("2026-11-23"), scheduler.firstDate(market, at("2026-11-21", 12)));
        assertEquals(LocalDate.parse("2026-11-30"), scheduler.firstDate(market, at("2026-11-27", 16)));
    }

    @Test void estimatedObservationDatesSkipWeekends() {
        var market = ecos();
        assertEquals(LocalDate.parse("2026-11-30"), scheduler.estimateDate(market, LocalDate.parse("2026-11-27"), 1));
        assertEquals(LocalDate.parse("2026-12-04"), scheduler.estimateDate(market, LocalDate.parse("2026-11-27"), 5));
    }
}
