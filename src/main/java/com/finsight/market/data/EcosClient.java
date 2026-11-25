package com.finsight.market.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.finsight.market.data.MarketDataProvider.*;

/**
 * Bank of Korea ECOS daily statistics: KOSPI/KOSDAQ index closes and the 3-year KTB yield.
 * Only values actually published by ECOS are returned; holidays are never filled in.
 */
@Component
public class EcosClient implements DailyObservationProvider {
    public static final String CALENDAR_CODE = "ECOS";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    /** dataSymbol is STAT_CODE/ITEM_CODE1. Each series is pinned to its unit and target type. */
    private static final Map<String, Series> SERIES = Map.of(
            "802Y001/0001000", new Series("point", "index"),
            "802Y001/0089000", new Series("point", "index"),
            "817Y002/010200000", new Series("%", "yield"));
    private final RestClient client;
    private final String apiKey;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public EcosClient(@Value("${market.data.ecos.api-key:${ECOS_API_KEY:}}") String apiKey,
                      @Value("${market.data.ecos.base-url:https://ecos.bok.or.kr}") String baseUrl) {
        this(createClient(baseUrl), apiKey);
    }

    EcosClient(RestClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    private static RestClient createClient(String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override public boolean supports(MarketTarget target) {
        return target != null && CALENDAR_CODE.equals(target.getCalendarCode());
    }

    @Override public String sourceName() { return "한국은행 경제통계시스템(ECOS)"; }
    @Override public String sourceUrl() { return "https://ecos.bok.or.kr/"; }

    @Override public List<Observation> observations(MarketTarget target, LocalDate through, int days) {
        var series = target == null ? null : SERIES.get(target.getDataSymbol());
        if (!supports(target) || series == null || !series.unit().equals(target.getUnit())
                || !series.targetType().equals(target.getTargetType()) || !SEOUL.getId().equals(target.getTimezone())
                || through == null || days < 1)
            throw new DataException(Status.unavailable, "ecos_target_mismatch");
        if (apiKey == null || apiKey.isBlank()) throw new DataException(Status.unavailable, "ecos_not_configured");
        var from = through.minusDays(days);
        String key = target.getDataSymbol() + "|" + from + "|" + through;
        var existing = cache.get(key);
        if (existing != null && System.nanoTime() - existing.loadedAt() < Duration.ofSeconds(60).toNanos())
            return existing.values();
        var values = fetch(target.getDataSymbol(), from, through, days + 1);
        if (cache.size() >= 256) cache.clear();
        cache.put(key, new Cached(System.nanoTime(), values));
        return values;
    }

    private List<Observation> fetch(String symbol, LocalDate from, LocalDate through, int rows) {
        String[] parts = symbol.split("/");
        try {
            // ECOS requires the key as a path segment. It is never logged or returned to clients.
            var body = client.get().uri(builder -> builder
                    .pathSegment("api", "StatisticSearch", apiKey, "json", "kr", "1", String.valueOf(Math.min(rows, 1000)),
                            parts[0], "D", from.format(BASIC), through.format(BASIC), parts[1]).build())
                    .header("User-Agent", "FinSight/1.0")
                    .retrieve().body(Response.class);
            return parse(body, parts[0], parts[1], from, through);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            throw new DataException(code == 429 || code >= 500 ? Status.pending : Status.unavailable, "ecos_http_" + code);
        } catch (RestClientException e) {
            throw new DataException(Status.pending, "ecos_transport_failure");
        }
    }

    static List<Observation> parse(Response body, String statCode, String itemCode, LocalDate from, LocalDate through) {
        if (body == null) throw new DataException(Status.unavailable, "ecos_empty_response");
        if (body.result() != null) {
            String code = body.result().code();
            // INFO-200: no data in the requested range (e.g. holidays or not yet published).
            if ("INFO-200".equals(code)) return List.of();
            if (code != null && code.startsWith("ERROR-")) throw new DataException(Status.pending, "ecos_" + code);
            throw new DataException(Status.unavailable, "ecos_" + code);
        }
        if (body.search() == null || body.search().rows() == null)
            throw new DataException(Status.unavailable, "ecos_missing_rows");
        List<Observation> result = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        try {
            for (var row : body.search().rows()) {
                if (row == null || !statCode.equals(row.statCode()) || !itemCode.equals(row.itemCode()))
                    throw new DataException(Status.unavailable, "ecos_series_mismatch");
                var date = LocalDate.parse(row.time(), BASIC);
                if (date.isBefore(from) || date.isAfter(through) || !dates.add(date))
                    throw new DataException(Status.unavailable, "ecos_date_mismatch");
                if (row.value() == null || row.value().isBlank()) continue;
                // Regular KRX close (15:30 KST). The KTB 3Y final quote is also stamped at this cut-off.
                result.add(new Observation(date, date.atTime(15, 30).atZone(SEOUL).toOffsetDateTime(),
                        new BigDecimal(row.value().trim())));
            }
        } catch (DateTimeException | NumberFormatException e) {
            throw new DataException(Status.unavailable, "invalid_ecos_response");
        }
        result.sort(Comparator.comparing(Observation::sessionDate));
        return List.copyOf(result);
    }

    record Series(String unit, String targetType) {}
    record Cached(long loadedAt, List<Observation> values) {}
    record Response(@JsonProperty("StatisticSearch") Search search, @JsonProperty("RESULT") Result result) {}
    record Search(@JsonProperty("row") List<Row> rows) {}
    record Result(@JsonProperty("CODE") String code, @JsonProperty("MESSAGE") String message) {}
    record Row(@JsonProperty("STAT_CODE") String statCode, @JsonProperty("ITEM_CODE1") String itemCode,
               @JsonProperty("TIME") String time, @JsonProperty("DATA_VALUE") String value) {}
}
