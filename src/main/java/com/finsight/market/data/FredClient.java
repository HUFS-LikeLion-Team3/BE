package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.finsight.market.data.MarketDataProvider.*;

/**
 * Federal Reserve Bank of St. Louis (FRED) daily index closes: S&P 500 and NASDAQ 100.
 * Uses the public CSV download. Blank values (market holidays) are skipped, never filled in.
 */
@Component
public class FredClient implements DailyObservationProvider {
    public static final String CALENDAR_CODE = "FRED";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final Set<String> SERIES = Set.of("SP500", "NASDAQ100");
    private final RestClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public FredClient(@Value("${market.data.fred.base-url:https://fred.stlouisfed.org}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    FredClient(RestClient client) { this.client = client; }

    @Override public boolean supports(MarketTarget target) {
        return target != null && CALENDAR_CODE.equals(target.getCalendarCode());
    }

    @Override public String sourceName() { return "Federal Reserve Bank of St. Louis (FRED)"; }
    @Override public String sourceUrl() { return "https://fred.stlouisfed.org/"; }

    @Override public List<Observation> observations(MarketTarget target, LocalDate through, int days) {
        if (!supports(target) || !SERIES.contains(target.getDataSymbol()) || !"point".equals(target.getUnit())
                || !"index".equals(target.getTargetType()) || !NEW_YORK.getId().equals(target.getTimezone())
                || through == null || days < 1)
            throw new DataException(Status.unavailable, "fred_target_mismatch");
        var from = through.minusDays(days);
        String key = target.getDataSymbol() + "|" + from + "|" + through;
        var existing = cache.get(key);
        if (existing != null && System.nanoTime() - existing.loadedAt() < Duration.ofSeconds(60).toNanos())
            return existing.values();
        String csv;
        try {
            csv = client.get().uri(builder -> builder.path("/graph/fredgraph.csv")
                            .queryParam("id", target.getDataSymbol())
                            .queryParam("cosd", from.toString()).queryParam("coed", through.toString()).build())
                    .header("User-Agent", "FinSight/1.0")
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            throw new DataException(code == 429 || code >= 500 ? Status.pending : Status.unavailable, "fred_http_" + code);
        } catch (RestClientException e) {
            throw new DataException(Status.pending, "fred_transport_failure");
        }
        var values = parse(csv, target.getDataSymbol(), from, through);
        if (cache.size() >= 256) cache.clear();
        cache.put(key, new Cached(System.nanoTime(), values));
        return values;
    }

    static List<Observation> parse(String csv, String series, LocalDate from, LocalDate through) {
        if (csv == null || csv.length() > 2_000_000) throw new DataException(Status.unavailable, "fred_csv_size");
        var lines = csv.strip().split("\\R");
        if (lines.length == 0 || !lines[0].trim().equalsIgnoreCase("observation_date," + series))
            throw new DataException(Status.unavailable, "fred_header_mismatch");
        List<Observation> result = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                var cells = lines[i].split(",", -1);
                if (cells.length != 2) throw new DataException(Status.unavailable, "invalid_fred_csv");
                var date = LocalDate.parse(cells[0].trim());
                if (!dates.add(date)) throw new DataException(Status.unavailable, "fred_duplicate_date");
                // FRED ignores an empty requested range and returns the whole series; keep only the window.
                if (date.isBefore(from) || date.isAfter(through)) continue;
                var value = cells[1].trim();
                if (value.isEmpty() || ".".equals(value)) continue;
                result.add(new Observation(date, date.atTime(16, 0).atZone(NEW_YORK).toOffsetDateTime(),
                        new BigDecimal(value)));
            }
        } catch (DateTimeException | NumberFormatException e) {
            throw new DataException(Status.unavailable, "invalid_fred_csv");
        }
        result.sort(Comparator.comparing(Observation::sessionDate));
        return List.copyOf(result);
    }

    record Cached(long loadedAt, List<Observation> values) {}
}
