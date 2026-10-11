package com.finsight.market.data;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class UsEquityCalendarTests {
    final ZoneId zone = ZoneId.of("America/New_York");
    @Test void officialHolidaysAndWeekendAreClosedButColumbusDayIsOpen() {
        for (String day : new String[]{"2026-04-03", "2026-07-03", "2026-10-11", "2026-11-26"}) {
            assertTrue(UsEquityCalendar.session("XNAS", zone, LocalDate.parse(day)).orElseThrow().isClosed());
        }
        var columbus = UsEquityCalendar.session("XNAS", zone, LocalDate.parse("2026-10-12")).orElseThrow();
        assertFalse(columbus.isClosed());
        assertEquals(LocalTime.of(16, 0), columbus.close());
    }
    @Test void publishedEarlyClosingsAreOnePm() {
        for (String day : new String[]{"2026-11-27", "2026-12-24"}) {
            assertEquals(LocalTime.of(13, 0), UsEquityCalendar.session("XNYS", zone, LocalDate.parse(day)).orElseThrow().close());
        }
        assertEquals(LocalTime.of(16, 0), UsEquityCalendar.session("XNAS", zone, LocalDate.parse("2026-07-02")).orElseThrow().close());
    }
    @Test void unsupportedYearsAndMarketsDoNotInventASchedule() {
        assertTrue(UsEquityCalendar.session("XNAS", zone, LocalDate.parse("2027-01-04")).isEmpty());
        assertTrue(UsEquityCalendar.session("XKRX", ZoneId.of("Asia/Seoul"), LocalDate.parse("2026-10-12")).isEmpty());
    }
}
