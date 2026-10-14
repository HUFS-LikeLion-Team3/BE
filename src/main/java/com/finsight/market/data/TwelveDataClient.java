package com.finsight.market.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import static com.finsight.market.data.MarketDataProvider.*;

@Component
public class TwelveDataClient implements MarketDataProvider, DailyObservationProvider {
    private final RestClient client;
    private final String apiKey;
    private final String calendarMode;
    private final Map<PriceKey, Cached<EndOfDay>> prices = new ConcurrentHashMap<>();
    private final Map<SessionKey, Cached<Session>> sessions = new ConcurrentHashMap<>();

    @Autowired
    public TwelveDataClient(@Value("${market.data.twelve-data.api-key:${TWELVE_DATA_API_KEY:}}") String apiKey,
                            @Value("${market.data.twelve-data.base-url:https://api.twelvedata.com}") String baseUrl,
                            @Value("${market.data.us-equity-calendar-mode:official}") String calendarMode) {
        this(createClient(baseUrl), apiKey, calendarMode);
    }

    TwelveDataClient(RestClient client, String apiKey) {
        this(client, apiKey, "provider");
    }

    TwelveDataClient(RestClient client, String apiKey, String calendarMode) {
        if (!Set.of("official", "provider").contains(calendarMode))
            throw new IllegalArgumentException("Invalid calendar mode");
        this.client = client;
        this.apiKey = apiKey;
        this.calendarMode = calendarMode;
    }

    private static RestClient createClient(String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override public String sourceName() { return "Twelve Data"; }
    @Override public String sourceUrl() { return "https://twelvedata.com"; }

    @Override public boolean supports(MarketTarget target) {
        return Set.of("TDFX", "TDGD").contains(target.getCalendarCode());
    }

    @Override public List<Observation> observations(MarketTarget target, LocalDate through, int days) {
        checkConfigured();
        String expectedSymbol = switch (target.getCalendarCode()) {
            case "TDFX" -> "USD/KRW";
            case "TDGD" -> "XAU/USD";
            case "TDWT" -> "WTI/USD";
            default -> throw new DataException(Status.unavailable);
        };
        String expectedUnit = switch (target.getCalendarCode()) {
            case "TDFX" -> "KRW";
            case "TDGD" -> "USD/troy_oz";
            default -> "USD/barrel";
        };
        var zone = ZoneId.of("Australia/Sydney");
        String expectedType = "TDFX".equals(target.getCalendarCode()) ? "exchange_rate" : "commodity";
        if (!expectedSymbol.equals(target.getDataSymbol()) || !expectedType.equals(target.getTargetType()) || !zone.getId().equals(target.getTimezone())
                || !expectedUnit.equals(target.getUnit())) throw new DataException(Status.unavailable, "daily_target_mismatch");
        try {
            var body = client.get().uri(builder -> builder.path("/time_series")
                    .queryParam("symbol", expectedSymbol).queryParam("interval", "1day")
                    .queryParam("outputsize", days + 1).queryParam("order", "desc")
                    .queryParam("dp", 6).queryParam("start_date", through.minusDays(days).toString())
                    .queryParam("end_date", through.plusDays(1).toString()).build())
                    .header("Authorization", "apikey " + apiKey).retrieve().body(TimeSeriesResponse.class);
            if (body == null) throw new DataException(Status.unavailable, "empty_daily_response");
            checkError(body.status(), body.code());
            if (body.meta() == null || !expectedSymbol.equals(body.meta().symbol())
                    || !"1day".equals(body.meta().interval())
                    || (body.meta().exchangeTimezone() != null && !zone.getId().equals(body.meta().exchangeTimezone()))
                    || body.values() == null)
                throw new DataException(Status.unavailable, "daily_metadata_mismatch");
            List<Observation> result = new ArrayList<>();
            for (var value : body.values()) {
                if (value == null || value.datetime() == null || value.close() == null)
                    throw new DataException(Status.unavailable, "incomplete_daily_bar");
                var date = LocalDate.parse(value.datetime());
                if (date.isBefore(through.minusDays(days)) || date.isAfter(through))
                    throw new DataException(Status.unavailable, "daily_date_outside_range");
                result.add(new Observation(date, date.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), value.close()));
            }
            return List.copyOf(result);
        } catch (RestClientResponseException e) {
            throw failure(e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new DataException(Status.pending, "daily_transport_failure");
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new DataException(Status.unavailable, "invalid_daily_response");
        }
    }

    @Override public EndOfDay latestEndOfDay(String symbol, String calendarCode) {
        return cached(prices, new PriceKey(symbol, calendarCode, null), Duration.ofSeconds(60),
                () -> fetchEndOfDay(symbol, calendarCode, null));
    }

    @Override public EndOfDay endOfDay(String symbol, String calendarCode, LocalDate date) {
        return cached(prices, new PriceKey(symbol, calendarCode, date), Duration.ofSeconds(60),
                () -> fetchEndOfDay(symbol, calendarCode, date));
    }

    private EndOfDay fetchEndOfDay(String symbol, String calendarCode, LocalDate date) {
        checkConfigured();
        try {
            var body = client.get().uri(builder -> {
                builder.path("/time_series").queryParam("symbol", symbol);
                // Nasdaq's operating MIC and the instrument's segment MIC can differ.
                if ("XNAS".equals(calendarCode)) builder.queryParam("exchange", "NASDAQ");
                else builder.queryParam("mic_code", calendarCode);
                builder.queryParam("interval", "1day").queryParam("outputsize", date == null ? 2 : 1)
                        .queryParam("order", "desc").queryParam("adjust", "none")
                        .queryParam("prepost", false).queryParam("dp", 6);
                // A date-only end bound is midnight; identical bounds contain no daily bar.
                if (date != null) builder.queryParam("start_date", date.toString())
                        .queryParam("end_date", date.plusDays(1).toString());
                return builder.build();
            }).header("Authorization", "apikey " + apiKey).retrieve().body(TimeSeriesResponse.class);
            if (body == null) throw new DataException(Status.unavailable);
            checkError(body.status(), body.code());
            if (body.meta() == null) throw new DataException(Status.unavailable, "missing_metadata");
            if (body.values() == null || body.values().isEmpty())
                throw new DataException(Status.unavailable, "missing_daily_values");
            if (!"1day".equals(body.meta().interval()))
                throw new DataException(Status.unavailable, "interval_mismatch");
            if (!matchesMarket(calendarCode, body.meta().micCode()))
                throw new DataException(Status.unavailable, "market_mismatch");
            if (!symbol.equals(body.meta().symbol()))
                throw new DataException(Status.unavailable, "symbol_mismatch");
            var value = body.values().getFirst();
            if (value == null || value.datetime() == null || value.close() == null)
                throw new DataException(Status.unavailable);
            LocalDate actualDate = LocalDate.parse(value.datetime());
            if (date != null && !date.equals(actualDate)) throw new DataException(Status.unavailable, "session_date_mismatch");
            return new EndOfDay(actualDate, value.close());
        } catch (RestClientResponseException e) {
            throw failure(e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new DataException(Status.pending);
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new DataException(Status.unavailable, "invalid_daily_response");
        }
    }

    private boolean matchesMarket(String calendarCode, String returnedMic) {
        return calendarCode.equals(returnedMic)
                || ("XNAS".equals(calendarCode) && "XNGS".equals(returnedMic));
    }

    @Override public Session session(String calendarCode, ZoneId timezone, LocalDate date) {
        if ("official".equals(calendarMode)) {
            var published = UsEquityCalendar.session(calendarCode, timezone, date);
            if (published.isPresent()) return published.get();
        }
        return cached(sessions, new SessionKey(calendarCode, timezone, date), Duration.ofMinutes(15),
                () -> fetchSession(calendarCode, timezone, date));
    }

    private Session fetchSession(String calendarCode, ZoneId timezone, LocalDate date) {
        checkConfigured();
        try {
            var body = client.get().uri(builder -> builder.path("/exchange_schedule")
                    .queryParam("mic_code", calendarCode).queryParam("date", date.toString()).build())
                    .header("Authorization", "apikey " + apiKey).retrieve().body(ScheduleResponse.class);
            if (body == null) throw new DataException(Status.unavailable);
            checkError(body.status(), body.code());
            if (body.data() == null) throw new DataException(Status.unavailable);
            var matches = body.data().stream().filter(day -> calendarCode.equals(day.code())).toList();
            if (matches.size() != 1) throw new DataException(Status.unavailable);
            var day = matches.getFirst();
            if (day.timezone() == null || !timezone.getRules().equals(ZoneId.of(day.timezone()).getRules())
                    || day.sessions() == null) throw new DataException(Status.unavailable);
            if (day.sessions().isEmpty()) return new Session(date, timezone, null, null);
            var regular = day.sessions().stream().filter(TradingSession::isRegular).toList();
            if (regular.isEmpty()) throw new DataException(Status.unavailable);
            LocalTime open = null;
            LocalTime close = null;
            for (var trading : regular) {
                var start = LocalTime.parse(trading.open());
                var end = LocalTime.parse(trading.close());
                // Overnight markets need their own provider; do not invent a session date.
                if (!start.isBefore(end)) throw new DataException(Status.unavailable);
                if (open == null || start.isBefore(open)) open = start;
                if (close == null || end.isAfter(close)) close = end;
            }
            return new Session(date, timezone, open, close);
        } catch (RestClientResponseException e) {
            throw failure(e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new DataException(Status.pending);
        } catch (DateTimeException | IllegalArgumentException | NullPointerException e) {
            throw new DataException(Status.unavailable);
        }
    }

    private void checkConfigured() {
        if (apiKey == null || apiKey.isBlank()) throw new DataException(Status.unavailable);
    }

    private <K, V> V cached(Map<K, Cached<V>> cache, K key, Duration ttl, Supplier<V> fetch) {
        long now = System.nanoTime();
        var existing = cache.get(key);
        if (existing != null && now - existing.fetchedAt() < ttl.toNanos()) return existing.value();
        V value = fetch.get(); // Failures are never cached.
        if (cache.size() >= 1024) cache.clear();
        cache.put(key, new Cached<>(value, System.nanoTime()));
        return value;
    }

    private record Cached<T>(T value, long fetchedAt) {}
    private record PriceKey(String symbol, String calendar, LocalDate date) {}
    private record SessionKey(String calendar, ZoneId timezone, LocalDate date) {}

    private void checkError(String status, Integer code) {
        if ("error".equals(status)) throw failure(code == null ? 500 : code);
    }

    private DataException failure(int code) {
        return new DataException(code == 429 || code >= 500 ? Status.pending : Status.unavailable, "provider_code_" + code);
    }

    record TimeSeriesResponse(SeriesMeta meta, List<DailyValue> values, String status, Integer code) {}
    record SeriesMeta(String symbol, String interval, @JsonProperty("mic_code") String micCode,
                      @JsonProperty("exchange_timezone") String exchangeTimezone) {}
    record DailyValue(String datetime, BigDecimal close) {}
    record ScheduleResponse(List<ExchangeDay> data, String status, Integer code) {}
    record ExchangeDay(String code, @JsonProperty("time_zone") String timezone, List<TradingSession> sessions) {}
    record TradingSession(@JsonProperty("open_time") String open, @JsonProperty("close_time") String close,
                          @JsonProperty("session_type") String type, @JsonProperty("session_name") String name) {
        boolean isRegular() {
            if ("pre".equalsIgnoreCase(type) || "post".equalsIgnoreCase(type)) return false;
            return Set.of("regular", "main", "normal").contains(type == null ? "" : type.toLowerCase(Locale.ROOT))
                    || "Main market".equalsIgnoreCase(name) || "Regular market".equalsIgnoreCase(name);
        }
    }
}
