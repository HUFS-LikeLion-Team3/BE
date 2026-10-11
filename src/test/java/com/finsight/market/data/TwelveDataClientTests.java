package com.finsight.market.data;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import static com.finsight.market.data.MarketDataProvider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TwelveDataClientTests {
    final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.twelvedata.com");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final TwelveDataClient client = new TwelveDataClient(builder.build(), "test-key");
    final LocalDate date = LocalDate.of(2026, 10, 9);
    final ZoneId zone = ZoneId.of("America/New_York");

    static String eod(String date, String close) {
        return """
            {"meta":{"symbol":"AAPL","mic_code":"XNAS","interval":"1day"},
             "values":[{"datetime":"%s","close":"%s"}],"status":"ok"}
            """.formatted(date, close);
    }

    static String schedule(String sessions) {
        return """
            {"data":[{"code":"XNAS","time_zone":"America/New_York","sessions":%s}]}
            """.formatted(sessions);
    }

    @Test void requestsUnadjustedRegularEodWithHeaderAndExactDate() {
        server.expect(requestTo("https://api.twelvedata.com/time_series?symbol=AAPL&exchange=NASDAQ&interval=1day&outputsize=1&order=desc&adjust=none&prepost=false&dp=6&start_date=2026-10-09&end_date=2026-10-10"))
                .andExpect(header("Authorization", "apikey test-key"))
                .andRespond(withSuccess(eod("2026-10-09", "225.123456"), MediaType.APPLICATION_JSON));
        assertEquals(new EndOfDay(date, new BigDecimal("225.123456")), client.endOfDay("AAPL", "XNAS", date));
        server.verify();
    }

    @Test void acceptsNasdaqGlobalSelectSegmentForNasdaqCalendar() {
        server.expect(requestTo("https://api.twelvedata.com/time_series?symbol=AAPL&exchange=NASDAQ&interval=1day&outputsize=1&order=desc&adjust=none&prepost=false&dp=6&start_date=2026-10-09&end_date=2026-10-10"))
                .andRespond(withSuccess(eod("2026-10-09", "225.123456").replace("XNAS", "XNGS"), MediaType.APPLICATION_JSON));
        assertEquals(new EndOfDay(date, new BigDecimal("225.123456")), client.endOfDay("AAPL", "XNAS", date));
        server.verify();
    }

    @Test void extractsEarlyRegularCloseAndExcludesPreAndPostMarket() {
        server.expect(requestTo("https://api.twelvedata.com/exchange_schedule?mic_code=XNAS&date=2026-10-09"))
                .andExpect(header("Authorization", "apikey test-key"))
                .andRespond(withSuccess(schedule("""
                    [{"open_time":"04:00:00","close_time":"09:30:00","session_type":"pre"},
                     {"open_time":"09:30:00","close_time":"13:00:00","session_type":"regular"},
                     {"open_time":"13:00:00","close_time":"20:00:00","session_type":"post"}]
                    """), MediaType.APPLICATION_JSON));
        var result = client.session("XNAS", zone, date);
        assertEquals(LocalTime.of(13, 0), result.close());
        server.verify();
    }

    @Test void explicitEmptySessionsMeanHoliday() {
        server.expect(anything()).andRespond(withSuccess(schedule("[]"), MediaType.APPLICATION_JSON));
        assertTrue(client.session("XNAS", zone, date).isClosed());
        server.verify();
    }

    @Test void unknownCalendarTimezoneAndSessionAreUnavailable() {
        for (String body : new String[]{"{\"data\":[]}", schedule("null"),
                schedule("[]").replace("America/New_York", "Asia/Seoul"),
                schedule("[{\"session_type\":\"post\",\"open_time\":\"16:00:00\",\"close_time\":\"20:00:00\"}]")}) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertEquals(Status.unavailable, assertThrows(DataException.class,
                    () -> client.session("XNAS", zone, date)).status());
            server.verify();
        }
    }

    @Test void neverUsesDifferentDateSymbolOrExchange() {
        for (String body : new String[]{eod("2026-10-08", "1"), eod("2026-10-09", "1").replace("AAPL", "OTHER"),
                eod("2026-10-09", "1").replace("XNAS", "XNYS")}) {
            server.reset();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertEquals(Status.unavailable, assertThrows(DataException.class,
                    () -> client.endOfDay("AAPL", "XNAS", date)).status());
            server.verify();
        }
    }

    @Test void classifiesHttpAndBodyErrorsWithoutReturningVendorMessage() {
        for (int code : new int[]{401, 403, 404, 429, 500}) {
            server.reset();
            server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(code)));
            var error = assertThrows(DataException.class, () -> client.latestEndOfDay("AAPL", "XNAS"));
            assertEquals(code == 429 || code >= 500 ? Status.pending : Status.unavailable, error.status());
            server.verify();
        }
        server.reset();
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"error\",\"code\":429,\"message\":\"test-key\"}", MediaType.APPLICATION_JSON));
        var error = assertThrows(DataException.class, () -> client.latestEndOfDay("AAPL", "XNAS"));
        assertEquals(Status.pending, error.status());
        assertFalse(error.getMessage().contains("test-key"));
        server.verify();
    }

    @Test void timeoutIsPendingAndMissingKeyMakesNoHttpRequest() {
        server.expect(anything()).andRespond(withException(new IOException("timeout")));
        assertEquals(Status.pending, assertThrows(DataException.class,
                () -> client.latestEndOfDay("AAPL", "XNAS")).status());
        server.verify();
        server.reset();
        var noKey = new TwelveDataClient(builder.build(), "");
        assertEquals(Status.unavailable, assertThrows(DataException.class,
                () -> noKey.latestEndOfDay("AAPL", "XNAS")).status());
        server.verify();
    }
}
