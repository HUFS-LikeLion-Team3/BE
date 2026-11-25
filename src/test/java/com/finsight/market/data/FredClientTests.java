package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import static com.finsight.market.data.MarketDataProvider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FredClientTests {
    final RestClient.Builder builder = RestClient.builder().baseUrl("https://fred.stlouisfed.org");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final FredClient client = new FredClient(builder.build());
    final ZoneId newYork = ZoneId.of("America/New_York");

    static MarketTarget target(String symbol) {
        var target = new MarketTarget();
        ReflectionTestUtils.setField(target, "calendarCode", "FRED");
        ReflectionTestUtils.setField(target, "dataSymbol", symbol);
        ReflectionTestUtils.setField(target, "timezone", "America/New_York");
        ReflectionTestUtils.setField(target, "unit", "point");
        ReflectionTestUtils.setField(target, "targetType", "index");
        return target;
    }

    @Test void readsPublishedClosesSkipsHolidaysAndStampsRegularClose() {
        server.expect(requestTo("https://fred.stlouisfed.org/graph/fredgraph.csv?id=SP500&cosd=2026-11-20&coed=2026-11-27"))
                .andRespond(withSuccess("observation_date,SP500\n2026-11-20,6910.39\n2026-11-23,7237.17\n2026-11-26,\n", MediaType.TEXT_PLAIN));
        var values = client.observations(target("SP500"), LocalDate.of(2026, 11, 27), 7);
        server.verify();
        assertEquals(2, values.size());
        assertEquals(LocalDate.of(2026, 11, 23), values.get(1).sessionDate());
        assertEquals(0, new BigDecimal("7237.17").compareTo(values.get(1).value()));
        assertEquals(LocalDate.of(2026, 11, 23).atTime(16, 0).atZone(newYork).toOffsetDateTime(), values.get(1).asOfAt());
    }

    @Test void ignoresWholeSeriesReturnedForEmptyWindow() {
        var values = FredClient.parse("observation_date,NASDAQ100\n2016-11-25,4800.1\n2026-11-24,26214.170\n",
                "NASDAQ100", LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 26));
        assertTrue(values.isEmpty());
    }

    @Test void rejectsWrongSeriesHeaderBadRowsAndUnknownTargets() {
        assertEquals(Status.unavailable, assertThrows(DataException.class, () -> FredClient.parse(
                "observation_date,NASDAQCOM\n2026-11-24,1\n", "NASDAQ100", LocalDate.MIN, LocalDate.MAX)).status());
        assertThrows(DataException.class, () -> FredClient.parse(
                "observation_date,SP500\n2026-11-24,abc\n", "SP500", LocalDate.MIN, LocalDate.MAX));
        assertThrows(DataException.class, () -> FredClient.parse(
                "observation_date,SP500\n2026-11-24,1\n2026-11-24,1\n", "SP500", LocalDate.MIN, LocalDate.MAX));
        assertThrows(DataException.class, () -> client.observations(target("DJIA"), LocalDate.of(2026, 11, 24), 1));
    }

    @Test void serverErrorIsPending() {
        server.expect(requestTo("https://fred.stlouisfed.org/graph/fredgraph.csv?id=NASDAQ100&cosd=2026-11-23&coed=2026-11-24"))
                .andRespond(withServerError());
        assertEquals(Status.pending, assertThrows(DataException.class,
                () -> client.observations(target("NASDAQ100"), LocalDate.of(2026, 11, 24), 1)).status());
    }

    @Test void nyseArcaUsesPublishedNyseSchedule() {
        var thanksgiving = UsEquityCalendar.session("ARCX", newYork, LocalDate.of(2026, 11, 26)).orElseThrow();
        assertTrue(thanksgiving.isClosed());
        var early = UsEquityCalendar.session("ARCX", newYork, LocalDate.of(2026, 11, 27)).orElseThrow();
        assertFalse(early.isClosed());
    }
}
