package com.finsight.market.data;

import java.time.*;
import java.util.*;
import com.finsight.market.data.MarketDataProvider.*;

/** Published 2026 cash-equity schedule, not a weekday approximation.
 * Sources: https://www.nasdaqtrader.com/Trader.aspx?id=Calendar
 *          https://www.nyse.com/trade/hours-calendars
 * Dates outside the published version must use a provider schedule.
 */
final class UsEquityCalendar {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-19"), LocalDate.parse("2026-02-16"),
            LocalDate.parse("2026-04-03"), LocalDate.parse("2026-05-25"), LocalDate.parse("2026-06-19"),
            LocalDate.parse("2026-07-03"), LocalDate.parse("2026-09-07"), LocalDate.parse("2026-11-26"),
            LocalDate.parse("2026-12-25"));
    private static final Set<LocalDate> EARLY_CLOSES = Set.of(
            LocalDate.parse("2026-11-27"), LocalDate.parse("2026-12-24"));

    static Optional<Session> session(String mic, ZoneId zone, LocalDate date) {
        if (!Set.of("XNAS", "XNYS").contains(mic) || date.getYear() != 2026) return Optional.empty();
        if (!NEW_YORK.equals(zone)) throw new DataException(Status.unavailable);
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
                || HOLIDAYS.contains(date)) return Optional.of(new Session(date, zone, null, null));
        return Optional.of(new Session(date, zone, LocalTime.of(9, 30),
                EARLY_CLOSES.contains(date) ? LocalTime.of(13, 0) : LocalTime.of(16, 0)));
    }
}
