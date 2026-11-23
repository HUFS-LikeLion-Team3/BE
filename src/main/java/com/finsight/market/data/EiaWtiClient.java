package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Pattern;

import static com.finsight.market.data.MarketDataProvider.*;

@Component
public class EiaWtiClient implements DailyObservationProvider {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static final Pattern ROW =
            Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>");

    private static final Pattern CELL =
            Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>");

    private static final Pattern WEEK =
            Pattern.compile(
                    "(?i)\\b(\\d{4})\\s+([A-Za-z]{3})\\s*-\\s*(\\d{1,2})\\s+to\\s+[A-Za-z]{3}\\s*-\\s*\\d{1,2}\\b"
            );

    private static final Pattern PRICE =
            Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final DateTimeFormatter MONTH =
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("MMM")
                    .toFormatter(Locale.US);

    private final RestClient client;
    private volatile Cache cache;

    @Autowired
    public EiaWtiClient(
            @Value("${market.data.eia-wti.base-url:https://www.eia.gov}")
            String baseUrl
    ) {
        this(createClient(baseUrl));
    }

    EiaWtiClient(RestClient client) {
        this.client = client;
    }

    private static RestClient createClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(25000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean supports(MarketTarget target) {
        return target != null
                && "TDWT".equals(target.getCalendarCode());
    }

    @Override
    public String sourceName() {
        return "U.S. Energy Information Administration";
    }

    @Override
    public String sourceUrl() {
        return "https://www.eia.gov/dnav/pet/hist/RWTCD.htm";
    }

    @Override
    public List<Observation> observations(
            MarketTarget target,
            LocalDate through,
            int days
    ) {
        if (!supports(target)
                || !"WTI/USD".equals(target.getDataSymbol())
                || !"commodity".equals(target.getTargetType())
                || !"USD/barrel".equals(target.getUnit())
                || through == null
                || days < 1) {
            throw new DataException(
                    Status.unavailable, "eia_target_mismatch"
            );
        }

        List<Observation> all = load();

        if (all.size() < 2) {
            throw new DataException(
                    Status.pending, "eia_insufficient_data"
            );
        }

        LocalDate today = LocalDate.now(
                ZoneId.of(target.getTimezone())
        );
        LocalDate latest = all.getLast().sessionDate();

        if (latest.isBefore(today.minusDays(14))) {
            throw new DataException(
                    Status.pending, "eia_stale_data"
            );
        }

        // Exact historical dates after the latest publication
        // must wait for EIA, not be treated as holidays.
        if (through.isAfter(latest) && !through.equals(today)) {
            throw new DataException(
                    Status.pending, "eia_publication_pending"
            );
        }

        LocalDate from = through.minusDays(days);

        return all.stream()
                .filter(o -> !o.sessionDate().isBefore(from))
                .filter(o -> !o.sessionDate().isAfter(through))
                .toList();
    }

    private synchronized List<Observation> load() {
        long now = System.currentTimeMillis();

        if (cache != null && now - cache.loadedAt < 1800000L) {
            return cache.values;
        }

        try {
            String html = client.get()
                    .uri("/dnav/pet/hist/RWTCD.htm")
                    .retrieve()
                    .body(String.class);

            List<Observation> values = parseHtml(html);

            if (values.size() < 2) {
                throw new DataException(
                        Status.pending, "eia_no_daily_prices"
                );
            }

            cache = new Cache(now, values);
            return values;

        } catch (DataException e) {
            throw e;
        } catch (RestClientException e) {
            throw new DataException(
                    Status.pending, "eia_provider_request_failed"
            );
        }
    }

    static List<Observation> parseHtml(String html) {
        if (html == null || !html.contains("Cushing")) {
            throw new DataException(
                    Status.unavailable, "invalid_eia_page"
            );
        }

        TreeMap<LocalDate, BigDecimal> prices =
                new TreeMap<>();

        var rows = ROW.matcher(html);

        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            var columns = CELL.matcher(rows.group(1));

            while (columns.find()) {
                cells.add(clean(columns.group(1)));
            }

            if (cells.size() < 6) {
                continue;
            }

            var week = WEEK.matcher(cells.getFirst());

            if (!week.find()) {
                continue;
            }

            LocalDate monday;

            try {
                int year = Integer.parseInt(week.group(1));
                Month month = Month.from(
                        MONTH.parse(week.group(2))
                );
                int day = Integer.parseInt(week.group(3));
                monday = LocalDate.of(year, month, day);

                if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
                    throw new IllegalArgumentException(
                            "Invalid EIA week"
                    );
                }
            } catch (RuntimeException e) {
                throw new DataException(
                        Status.unavailable, "invalid_eia_week"
                );
            }

            for (int i = 1; i <= 5; i++) {
                String raw = cells.get(i);

                if (!PRICE.matcher(raw).matches()) {
                    continue;
                }

                LocalDate date = monday.plusDays(i - 1);
                BigDecimal value = new BigDecimal(raw);

                if (value.signum() <= 0) {
                    throw new DataException(
                            Status.unavailable, "invalid_eia_price"
                    );
                }

                BigDecimal previous = prices.putIfAbsent(
                        date, value
                );

                if (previous != null
                        && previous.compareTo(value) != 0) {
                    throw new DataException(
                            Status.unavailable,
                            "conflicting_eia_price"
                    );
                }
            }
        }

        List<Observation> result = new ArrayList<>();

        for (var entry : prices.entrySet()) {
            LocalDate date = entry.getKey();

            // End-of-observation-day convention.
            // This is not the EIA publication timestamp.
            OffsetDateTime asOfAt = date.plusDays(1)
                    .atStartOfDay(CHICAGO)
                    .toOffsetDateTime();

            result.add(new Observation(
                    date, asOfAt, entry.getValue()
            ));
        }

        return List.copyOf(result);
    }

    private static String clean(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("\u00a0", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record Cache(
            long loadedAt,
            List<Observation> values
    ) {}
}