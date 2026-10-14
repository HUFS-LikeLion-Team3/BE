package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import static com.finsight.market.data.MarketDataProvider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataServiceTests {
    final MarketDataProvider provider = mock(MarketDataProvider.class);
    final ZoneId zone = ZoneId.of("America/New_York");
    final LocalDate monday = LocalDate.of(2026, 10, 5);
    final LocalDate friday = monday.minusDays(3);
    final MarketTarget target = target();

    static MarketTarget target() {
        var target = new MarketTarget();
        ReflectionTestUtils.setField(target, "calendarCode", "XNAS");
        ReflectionTestUtils.setField(target, "timezone", "America/New_York");
        ReflectionTestUtils.setField(target, "dataSymbol", "AAPL");
        ReflectionTestUtils.setField(target, "unit", "USD");
        return target;
    }

    MarketDataService service(String now) {
        return new MarketDataService(provider, Clock.fixed(Instant.parse(now), ZoneOffset.UTC), Duration.ofMinutes(15), 14);
    }

    void trading(LocalDate date, int hour) {
        when(provider.session("XNAS", zone, date)).thenReturn(new Session(date, zone, LocalTime.of(9, 30), LocalTime.of(hour, 0)));
    }

    void holiday(LocalDate date) {
        when(provider.session("XNAS", zone, date)).thenReturn(new Session(date, zone, null, null));
    }

    void eod(LocalDate date, String value) {
        when(provider.endOfDay("AAPL", "XNAS", date)).thenReturn(new EndOfDay(date, new BigDecimal(value)));
    }

    void weekend() {
        holiday(monday.minusDays(1));
        holiday(monday.minusDays(2));
        trading(friday, 16);
    }

    @Test void skipsWeekendAndUsesRegularCloseTimestampRatherThanCollectionTime() {
        trading(monday, 16);
        weekend();
        when(provider.latestEndOfDay("AAPL", "XNAS")).thenReturn(new EndOfDay(monday, new BigDecimal("225.50")));
        when(provider.sourceName()).thenReturn("Twelve Data");
        when(provider.sourceUrl()).thenReturn("https://twelvedata.com");
        eod(friday, "220.00");
        var result = service("2026-10-05T22:00:00Z").latestRegularCloses(target);
        assertEquals(Status.ready, result.status());
        assertEquals(monday, result.data().latest().sessionDate());
        assertEquals(OffsetDateTime.parse("2026-10-05T16:00:00-04:00"), result.data().latest().asOfAt());
        assertEquals(friday, result.data().previous().sessionDate());
        assertEquals(new BigDecimal("220.00"), result.data().previous().value());
    }

    @Test void intradayAndUnconfirmedCloseAreExcluded() {
        for (String now : new String[]{"2026-10-05T18:00:00Z", "2026-10-05T20:05:00Z"}) {
            trading(monday, 16);
            weekend();
            trading(friday.minusDays(1), 16);
            when(provider.latestEndOfDay("AAPL", "XNAS")).thenReturn(new EndOfDay(monday, new BigDecimal("999")));
            eod(friday, "225");
            eod(friday.minusDays(1), "220");
            var result = service(now).latestRegularCloses(target);
            assertEquals(Status.ready, result.status());
            assertEquals(friday, result.data().latest().sessionDate());
            assertEquals(new BigDecimal("225"), result.data().latest().value());
        }
        verify(provider, never()).endOfDay("AAPL", "XNAS", monday);
    }

    @Test void missingImmediatePreviousCloseIsNotReplacedByOlderClose() {
        trading(monday, 16);
        weekend();
        when(provider.latestEndOfDay("AAPL", "XNAS")).thenReturn(new EndOfDay(monday, BigDecimal.ONE));
        when(provider.endOfDay("AAPL", "XNAS", friday)).thenThrow(new DataException(Status.unavailable));
        var result = service("2026-10-05T22:00:00Z").latestRegularCloses(target);
        assertEquals(Status.unavailable, result.status());
        assertNull(result.data());
        verify(provider, never()).session("XNAS", zone, friday.minusDays(1));
    }

    @Test void staleLatestDataRemainsPending() {
        trading(monday, 16);
        when(provider.latestEndOfDay("AAPL", "XNAS")).thenReturn(new EndOfDay(friday, BigDecimal.ONE));
        assertEquals(Status.pending, service("2026-10-05T22:00:00Z").latestRegularCloses(target).status());
    }

    @Test void historicalQueryUsesExactDateAndEarlyClose() {
        trading(monday, 13);
        eod(monday, "225");
        var result = service("2026-10-05T18:00:00Z").regularCloseOn(target, monday);
        assertEquals(Status.ready, result.status());
        assertEquals(OffsetDateTime.parse("2026-10-05T13:00:00-04:00"), result.data().asOfAt());
        verify(provider, never()).latestEndOfDay(anyString(), anyString());
    }

    @Test void futureAndUnconfirmedHistoricalCloseNeverMakesAnEodRequest() {
        trading(monday, 16);
        var result = service("2026-10-05T20:05:00Z").regularCloseOn(target, monday);
        assertEquals(Status.pending, result.status());
        verify(provider, never()).endOfDay(anyString(), anyString(), any());
    }

    @Test void historicalHolidayIsUnavailableAndDoesNotQueryPrices() {
        holiday(monday);
        assertEquals(Status.unavailable, service("2026-10-05T22:00:00Z").regularCloseOn(target, monday).status());
        verify(provider, never()).endOfDay(anyString(), anyString(), any());
    }

    @Test void dstUsesSessionDatesOffset() {
        LocalDate winter = LocalDate.of(2026, 11, 2);
        trading(winter, 16);
        eod(winter, "225");
        var result = service("2026-11-02T22:00:00Z").regularCloseOn(target, winter);
        assertEquals(OffsetDateTime.parse("2026-11-02T16:00:00-05:00"), result.data().asOfAt());
    }

    @Test void failuresAndInvalidTargetsReturnNoData() {
        trading(monday, 16);
        when(provider.latestEndOfDay("AAPL", "XNAS")).thenThrow(new DataException(Status.pending));
        assertEquals(Status.pending, service("2026-10-05T22:00:00Z").latestRegularCloses(target).status());
        clearInvocations(provider);
        ReflectionTestUtils.setField(target, "calendarCode", "SAMPLE");
        assertEquals(Status.unavailable, service("2026-10-05T22:00:00Z").latestRegularCloses(target).status());
        verifyNoInteractions(provider);
    }
}
