package com.finsight.market.data;

import tools.jackson.databind.ObjectMapper;
import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real requests. No database or mock HTTP. Reports each market independently. */
@EnabledIfEnvironmentVariable(named = "FINSIGHT_LIVE_MARKETS_TEST", matches = "true")
class LiveMarketsTests {
    @Test void verifiesConfiguredMarketsWithActualProviders() throws Exception {
        var twelve = new TwelveDataClient(System.getenv("TWELVE_DATA_API_KEY"), "https://api.twelvedata.com", "official");
        var treasury = new TreasuryYieldClient("https://home.treasury.gov");
        var service = new MarketDataService(twelve, List.of(twelve, treasury, new EiaWtiClient("https://www.eia.gov")), Duration.ofMinutes(15), 14);
        String selected = System.getenv("FINSIGHT_LIVE_TARGETS");
        var codes = selected == null || selected.isBlank() ? Set.of("KOSPI", "US10Y", "USDKRW", "GOLD", "WTI")
                : Set.of(selected.split(","));
        List<Map<String, Object>> reports = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (var code : new TreeSet<>(codes)) {
            MarketTarget target = switch (code) {
                case "KOSPI" -> DailyMarketDataTests.target("XKRX", "KS11", "Asia/Seoul", "point", "index");
                case "US10Y" -> DailyMarketDataTests.target("USTY", "BC_10YEAR", "America/New_York", "%", "yield");
                case "USDKRW" -> DailyMarketDataTests.target("TDFX", "USD/KRW", "Australia/Sydney", "KRW", "exchange_rate");
                case "GOLD" -> DailyMarketDataTests.target("TDGD", "XAU/USD", "Australia/Sydney", "USD/troy_oz", "commodity");
                case "WTI" -> DailyMarketDataTests.target("TDWT", "WTI/USD", "Australia/Sydney", "USD/barrel", "commodity");
                default -> throw new IllegalArgumentException("Unknown live target: " + code);
            };
            var result = service.latestRegularCloses(target);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", result.status().name());
            row.put("unit", target.getUnit());
            row.put("diagnostic", result.diagnostic());
            if (result.status() == MarketDataProvider.Status.ready) {
                var pair = result.data();
                row.put("sessionDate", pair.latest().sessionDate().toString());
                row.put("asOfAt", pair.latest().asOfAt().toString());
                row.put("value", pair.latest().value());
                row.put("previousSessionDate", pair.previous().sessionDate().toString());
                row.put("previousCloseValue", pair.previous().value());
                row.put("dataSourceName", pair.latest().dataSourceName());
                row.put("dataSourceUrl", pair.latest().dataSourceUrl());
            } else {
                failures.add(code + ": " + result.status() + ", " + result.diagnostic());
            }
            reports.add(row);
        }
        var output = Path.of("build", "reports", "markets-live.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(reports));
        assertTrue(failures.isEmpty(), "Actual market verification failed: " + String.join("; ", failures));
    }
}
