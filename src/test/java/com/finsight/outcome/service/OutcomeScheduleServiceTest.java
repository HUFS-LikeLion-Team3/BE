package com.finsight.outcome.service;

import com.finsight.market.data.MarketDataProvider;
import com.finsight.market.data.MarketDataService;
import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutcomeScheduleServiceTest {

    private static final ZoneId NEW_YORK =
            ZoneId.of("America/New_York");

    private MarketDataService marketDataService;
    private OutcomeScheduleService scheduleService;
    private MarketTarget target;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        target = mock(MarketTarget.class);

        when(target.getCalendarCode()).thenReturn("XNAS");
        when(target.getTimezone()).thenReturn("America/New_York");

        scheduleService = new OutcomeScheduleService(
                marketDataService
        );

        mockCalendar(Set.of());
    }

    private void mockCalendar(Set<LocalDate> holidays) {
        when(marketDataService.regularSessionOn(
                eq(target),
                any(LocalDate.class)
        )).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);

            boolean closed =
                    date.getDayOfWeek() == DayOfWeek.SATURDAY
                            || date.getDayOfWeek() == DayOfWeek.SUNDAY
                            || holidays.contains(date);

            if (closed) {
                return new MarketDataProvider.Session(
                        date,
                        NEW_YORK,
                        null,
                        null
                );
            }

            return new MarketDataProvider.Session(
                    date,
                    NEW_YORK,
                    LocalTime.of(9, 30),
                    LocalTime.of(16, 0)
            );
        });
    }

    @Test
    void baselineBeforeCloseStartsSameTradingDay() {
        OffsetDateTime submittedAt =
                OffsetDateTime.parse(
                        "2026-10-09T15:00:00-04:00"
                );

        OutcomeScheduleService.BaselineSchedule result =
                scheduleService.calculateBaselineSchedule(
                        target,
                        submittedAt
                );

        assertEquals(
                LocalDate.of(2026, 10, 9),
                result.expectedDate()
        );

        assertEquals(
                LocalDate.of(2026, 10, 23),
                result.deadlineDate()
        );
    }

    @Test
    void baselineAfterCloseStartsNextTradingDay() {
        OffsetDateTime submittedAt =
                OffsetDateTime.parse(
                        "2026-10-09T16:00:00-04:00"
                );

        OutcomeScheduleService.BaselineSchedule result =
                scheduleService.calculateBaselineSchedule(
                        target,
                        submittedAt
                );

        assertEquals(
                LocalDate.of(2026, 10, 12),
                result.expectedDate()
        );

        assertEquals(
                LocalDate.of(2026, 10, 26),
                result.deadlineDate()
        );
    }

    @Test
    void baselineSkipsMarketHolidays() {
        mockCalendar(
                Set.of(LocalDate.of(2026, 10, 12))
        );

        OffsetDateTime submittedAt =
                OffsetDateTime.parse(
                        "2026-10-09T16:30:00-04:00"
                );

        OutcomeScheduleService.BaselineSchedule result =
                scheduleService.calculateBaselineSchedule(
                        target,
                        submittedAt
                );

        assertEquals(
                LocalDate.of(2026, 10, 13),
                result.expectedDate()
        );

        assertEquals(
                LocalDate.of(2026, 10, 27),
                result.deadlineDate()
        );
    }

    @Test
    void observationDatesUseActualBaselineDate() {
        Map<Integer, LocalDate> result =
                scheduleService.calculateObservationDates(
                        target,
                        LocalDate.of(2026, 10, 9)
                );

        assertEquals(
                LocalDate.of(2026, 10, 12),
                result.get(1)
        );

        assertEquals(
                LocalDate.of(2026, 10, 16),
                result.get(5)
        );

        assertEquals(
                LocalDate.of(2026, 11, 6),
                result.get(20)
        );
    }

    @Test
    void observationDatesExcludeHolidays() {
        mockCalendar(
                Set.of(LocalDate.of(2026, 10, 12))
        );

        Map<Integer, LocalDate> result =
                scheduleService.calculateObservationDates(
                        target,
                        LocalDate.of(2026, 10, 9)
                );

        assertEquals(
                LocalDate.of(2026, 10, 13),
                result.get(1)
        );

        assertEquals(
                LocalDate.of(2026, 10, 19),
                result.get(5)
        );

        assertEquals(
                LocalDate.of(2026, 11, 9),
                result.get(20)
        );
    }

    @Test
    void dailyMarketsRequireSeparateCalendarPolicy() {
        when(target.getCalendarCode()).thenReturn("USTY");

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduleService.calculateBaselineSchedule(
                        target,
                        OffsetDateTime.parse(
                                "2026-10-09T15:00:00-04:00"
                        )
                )
        );
    }

    @Test
    void providerFailureMustNotBeTreatedAsHoliday() {
        when(marketDataService.regularSessionOn(
                eq(target),
                eq(LocalDate.of(2026, 10, 12))
        )).thenThrow(
                new MarketDataProvider.DataException(
                        MarketDataProvider.Status.pending,
                        "calendar_temporarily_unavailable"
                )
        );

        MarketDataProvider.DataException exception =
                assertThrows(
                        MarketDataProvider.DataException.class,
                        () -> scheduleService.calculateObservationDates(
                                target,
                                LocalDate.of(2026, 10, 9)
                        )
                );

        assertEquals(
                MarketDataProvider.Status.pending,
                exception.status()
        );
    }
}