package com.finsight.outcome.service;

import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyOutcomeSubmissionBoundaryTest {
    private final DailyOutcomeScheduler scheduler = new DailyOutcomeScheduler(null, null, null, null, null, null, null);

    @Test void weekendSubmissionKeepsFirstEligibleDailyBarForFxAndCommodities() {
        for (String code : List.of("TDFX", "TDGD", "TDWT")) {
            var market = mock(MarketTarget.class);
            when(market.getCalendarCode()).thenReturn(code);
            when(market.getTimezone()).thenReturn("Australia/Sydney");
            assertEquals(LocalDate.of(2026, 10, 10), scheduler.firstDate(market,
                    OffsetDateTime.parse("2026-10-10T12:00:00+11:00")));
            // Friday's daily bar has already closed at this exact Saturday midnight.
            assertEquals(LocalDate.of(2026, 10, 10), scheduler.firstDate(market,
                    OffsetDateTime.parse("2026-10-10T00:00:00+11:00")));
            assertEquals(LocalDate.of(2026, 10, 11), scheduler.estimateDate(market, LocalDate.of(2026, 10, 10), 1));
        }
    }

    @Test void treasuryRetainsBusinessDayPolicyAndStrictSubmissionBoundary() {
        var market = mock(MarketTarget.class);
        when(market.getCalendarCode()).thenReturn("USTY");
        when(market.getTimezone()).thenReturn("America/New_York");
        assertEquals(LocalDate.of(2026, 10, 12), scheduler.firstDate(market,
                OffsetDateTime.parse("2026-10-10T12:00:00-04:00")));
        assertEquals(LocalDate.of(2026, 10, 9), scheduler.firstDate(market,
                OffsetDateTime.parse("2026-10-09T15:29:59-04:00")));
        assertEquals(LocalDate.of(2026, 10, 12), scheduler.firstDate(market,
                OffsetDateTime.parse("2026-10-09T15:30:00-04:00")));
    }
}
