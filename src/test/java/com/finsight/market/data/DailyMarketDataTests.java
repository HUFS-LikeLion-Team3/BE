package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.finsight.market.data.MarketDataProvider.*;

class DailyMarketDataTests {
    static MarketTarget target(String code, String symbol, String zone, String unit, String type) {
        var target = new MarketTarget();
        ReflectionTestUtils.setField(target, "calendarCode", code);
        ReflectionTestUtils.setField(target, "dataSymbol", symbol);
        ReflectionTestUtils.setField(target, "timezone", zone);
        ReflectionTestUtils.setField(target, "unit", unit);
        ReflectionTestUtils.setField(target, "targetType", type);
        return target;
    }

    @Test void forexUsesSydneyDailyBoundaryAndExcludesCurrentUnfinishedBar() {
        var builder = RestClient.builder().baseUrl("https://api.twelvedata.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new TwelveDataClient(builder.build(), "test-key");
        var target = target("TDFX", "USD/KRW", "Australia/Sydney", "KRW", "exchange_rate");
        server.expect(requestTo("https://api.twelvedata.com/time_series?symbol=USD/KRW&interval=1day&outputsize=15&order=desc&dp=6&start_date=2026-09-27&end_date=2026-10-12"))
                .andExpect(header("Authorization", "apikey test-key"))
                .andRespond(withSuccess("""
                    {"meta":{"symbol":"USD/KRW","interval":"1day","currency_base":"US Dollar","currency_quote":"South Korean Won","type":"Physical Currency"},
                     "values":[{"datetime":"2026-10-11","close":"1400"},
                               {"datetime":"2026-10-09","close":"1380.1"},
                               {"datetime":"2026-10-08","close":"1375.2"}],"status":"ok"}
                    """, MediaType.APPLICATION_JSON));
        var service = new MarketDataService(client, List.of(client),
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 14);
        var result = service.latestRegularCloses(target);
        assertEquals(Status.ready, result.status());
        assertEquals(new BigDecimal("1380.1"), result.data().latest().value());
        assertEquals(OffsetDateTime.parse("2026-10-10T00:00:00+11:00"), result.data().latest().asOfAt());
        assertEquals(new BigDecimal("1375.2"), result.data().previous().value());
        assertThrows(DataException.class, () -> service.regularSessionOn(target, LocalDate.of(2026, 10, 9)));
        server.verify();
    }

    @Test void identicalDuplicatesAreCollapsedButConflictingPricesAreRejectedForLatestAndExactDate() {
        for (String duplicate : List.of("100.00", "101")) {
            var builder = RestClient.builder().baseUrl("https://api.twelvedata.com");
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new TwelveDataClient(builder.build(), "test-key");
            String body = """
                {"meta":{"symbol":"XAU/USD","interval":"1day"},"values":[
                  {"datetime":"2026-10-09","close":"100"},
                  {"datetime":"2026-10-09","close":"%s"},
                  {"datetime":"2026-10-08","close":"99"},
                  {"datetime":"2026-10-04","close":"95"},
                  {"datetime":"2026-10-04","close":"96"}]}
                """.formatted(duplicate);
            server.expect(org.springframework.test.web.client.ExpectedCount.twice(), anything())
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess("""
                {"meta":{"symbol":"XAU/USD","interval":"1day"},"values":[
                  {"datetime":"2026-10-04","close":"95"},
                  {"datetime":"2026-10-04","close":"96"}]}
                """, MediaType.APPLICATION_JSON));
            var target = target("TDGD", "XAU/USD", "Australia/Sydney", "USD/troy_oz", "commodity");
            var service = new MarketDataService(client, List.of(client),
                    Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 14);
            var latest = service.latestRegularCloses(target);
            var exact = service.regularCloseOn(target, LocalDate.of(2026, 10, 9));
            var conflictedDate = service.regularCloseOn(target, LocalDate.of(2026, 10, 4));
            assertEquals(Status.unavailable, conflictedDate.status());
            assertTrue(conflictedDate.diagnostic().contains("conflicting_observation_date"));
            if (duplicate.equals("100.00")) {
                assertEquals(Status.ready, latest.status());
                assertEquals(LocalDate.of(2026, 10, 8), latest.data().previous().sessionDate());
                assertEquals(Status.ready, exact.status());
            } else {
                assertEquals(Status.unavailable, latest.status());
                assertTrue(latest.diagnostic().contains("conflicting_observation_date"));
                assertEquals(Status.unavailable, exact.status());
            }
            server.verify();
        }
    }

    @Test void wrongDailyTimezoneIsRejectedInsteadOfGuessingBoundary() {
        var builder = RestClient.builder().baseUrl("https://api.twelvedata.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new TwelveDataClient(builder.build(), "test-key");
        server.expect(anything()).andRespond(withSuccess("""
            {"meta":{"symbol":"XAU/USD","interval":"1day","exchange_timezone":"UTC"},"values":[]}
            """, MediaType.APPLICATION_JSON));
        var target = target("TDGD", "XAU/USD", "Australia/Sydney", "USD/troy_oz", "commodity");
        assertEquals(Status.unavailable, assertThrows(DataException.class,
                () -> client.observations(target, LocalDate.of(2026, 10, 11), 14)).status());
        server.verify();
    }

    @Test void treasuryParYieldRemainsPercentAndUsesValuationTime() {
        var builder = RestClient.builder().baseUrl("https://home.treasury.gov");
        var server = MockRestServiceServer.bindTo(builder).build();
        var treasury = new TreasuryYieldClient(builder.build());
        server.expect(requestTo("https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value=2026"))
                .andRespond(withSuccess(xml("2026-10-08", "4.20", "2026-10-09", "4.25"), MediaType.APPLICATION_XML));
        var target = target("USTY", "BC_10YEAR", "America/New_York", "%", "yield");
        var service = new MarketDataService(new TwelveDataClient("", "https://api.twelvedata.com", "official"),
                List.of(treasury), Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 14);
        var result = service.latestRegularCloses(target);
        assertEquals(Status.ready, result.status());
        assertEquals(new BigDecimal("4.25"), result.data().latest().value());
        assertEquals(OffsetDateTime.parse("2026-10-09T15:30:00-04:00"), result.data().latest().asOfAt());
        assertEquals(new BigDecimal("4.20"), result.data().previous().value());
        assertEquals(Status.unavailable, service.regularCloseOn(target, LocalDate.of(2026, 10, 7)).status());
        server.verify();
    }

    @Test void nullYieldAndXmlEntitiesAreRejected() {
        assertThrows(DataException.class, () -> TreasuryYieldClient.parse(xml("2026-10-08", "", "2026-10-09", "4.25"), 2026));
        assertThrows(DataException.class, () -> TreasuryYieldClient.parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///invalid'>]><feed>&x;</feed>", 2026));
    }

    static String xml(String firstDate, String firstValue, String secondDate, String secondValue) {
        return """
            <feed xmlns:m="http://schemas.microsoft.com/ado/2007/08/dataservices/metadata"
                  xmlns:d="http://schemas.microsoft.com/ado/2007/08/dataservices">
              <m:properties><d:NEW_DATE>%sT00:00:00</d:NEW_DATE><d:BC_10YEAR>%s</d:BC_10YEAR></m:properties>
              <m:properties><d:NEW_DATE>%sT00:00:00</d:NEW_DATE><d:BC_10YEAR>%s</d:BC_10YEAR></m:properties>
            </feed>
            """.formatted(firstDate, firstValue, secondDate, secondValue);
    }
}
