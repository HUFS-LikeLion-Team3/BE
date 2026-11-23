package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import com.finsight.market.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only: real requests with the operator's key, no mocks or database changes. */
@EnabledIfEnvironmentVariable(named = "FINSIGHT_LIVE_MARKET_TEST", matches = "true")
class LiveMarketDataTests {
    @Test void fetchesActualAaplClosesThroughFinSightReferenceService() throws Exception {
        String key = System.getenv("TWELVE_DATA_API_KEY");
        assertNotNull(key, "TWELVE_DATA_API_KEY is required");
        assertFalse(key.isBlank(), "TWELVE_DATA_API_KEY is required");
        var client = new TwelveDataClient(key, "https://api.twelvedata.com", "official");
        MarketDataProvider.EndOfDay latest;
        try {
            latest = client.latestEndOfDay("AAPL", "XNAS");
        } catch (MarketDataProvider.DataException e) {
            throw new AssertionError("Java latest daily close lookup failed: " + e.getMessage());
        }
        var previousDate = latest.date().minusDays(1);
        while (client.session("XNAS", java.time.ZoneId.of("America/New_York"), previousDate).isClosed())
            previousDate = previousDate.minusDays(1);
        try {
            client.endOfDay("AAPL", "XNAS", previousDate);
        } catch (MarketDataProvider.DataException e) {
            throw new AssertionError("Java previous daily close lookup failed for " + previousDate + ": " + e.getMessage());
        }
        var data = new MarketDataService(client, Duration.ofMinutes(15), 14);
        MarketTarget target = MarketDataServiceTests.target();
        ReflectionTestUtils.setField(target, "targetType", "stock");
        ReflectionTestUtils.setField(target, "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "큰 방향성 없음"));
        var result = new ReferenceMarketService(new LiveReferenceMarketProvider(data)).get(target);
        assertEquals("ready", result.status(), "Live data not ready. Check API key/plan, symbol and provider response.");
        assertNotNull(result.value());
        assertNotNull(result.previousCloseValue());
        assertEquals(result.value().subtract(result.previousCloseValue()), result.changeValue());
        var output = Path.of("build", "reports", "market-data-live.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, """
            {
              "status": "ready",
              "symbol": "AAPL",
              "sessionDate": "%s",
              "asOfAt": "%s",
              "value": %s,
              "previousCloseValue": %s,
              "changeValue": %s,
              "changePercent": %s,
              "unit": "USD",
              "dataSourceName": "Twelve Data",
              "dataSourceUrl": "https://twelvedata.com",
              "unavailableReason": null
            }
            """.formatted(result.sessionDate(), result.asOfAt(), result.value().toPlainString(),
                result.previousCloseValue().toPlainString(), result.changeValue().toPlainString(), result.changePercent()));
    }
}
