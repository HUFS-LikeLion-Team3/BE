package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.finsight.market.data.MarketDataProvider.*;

/** Treasury 10-year constant-maturity par yield, percent; not a bond or ETF price. */
@Component
public class TreasuryYieldClient implements DailyObservationProvider {
    private static final String DATA_NS = "http://schemas.microsoft.com/ado/2007/08/dataservices";
    private static final String META_NS = DATA_NS + "/metadata";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final RestClient client;
    private final Map<Integer, CachedYear> cache = new HashMap<>();

    @Autowired
    public TreasuryYieldClient(@Value("${market.data.treasury.base-url:https://home.treasury.gov}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    TreasuryYieldClient(RestClient client) { this.client = client; }

    @Override public boolean supports(MarketTarget target) { return "USTY".equals(target.getCalendarCode()); }
    @Override public String sourceName() { return "U.S. Department of the Treasury"; }
    @Override public String sourceUrl() {
        return "https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics";
    }

    @Override public List<Observation> observations(MarketTarget target, LocalDate through, int days) {
        if (!"BC_10YEAR".equals(target.getDataSymbol()) || !"%".equals(target.getUnit())
                || !NEW_YORK.getId().equals(target.getTimezone()) || !"yield".equals(target.getTargetType()))
            throw new DataException(Status.unavailable, "treasury_target_mismatch");
        var from = through.minusDays(days);
        List<Observation> result = new ArrayList<>();
        for (int year = from.getYear(); year <= through.getYear(); year++) {
            for (var value : year(year)) {
                if (!value.sessionDate().isBefore(from) && !value.sessionDate().isAfter(through)) result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private synchronized List<Observation> year(int year) {
        var existing = cache.get(year);
        if (existing != null && System.nanoTime() - existing.loadedAt() < Duration.ofSeconds(60).toNanos())
            return existing.values();
        try {
            String xml = client.get().uri(builder -> builder
                    .path("/resource-center/data-chart-center/interest-rates/pages/xml")
                    .queryParam("data", "daily_treasury_yield_curve")
                    .queryParam("field_tdr_date_value", year).build())
                    .header("Accept", "application/xml")
                    .header("User-Agent", "FinSight/1.0")
                    .retrieve().body(String.class);
            List<Observation> values = parse(xml, year);
            if (cache.size() >= 3) cache.clear();
            cache.put(year, new CachedYear(System.nanoTime(), values));
            return values;
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            throw new DataException(code == 429 || code >= 500 ? Status.pending : Status.unavailable,
                    "treasury_http_" + code);
        } catch (RestClientException e) {
            throw new DataException(Status.pending, "treasury_transport_" + e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    static List<Observation> parse(String xml, int requestedYear) {
        try {
            if (xml == null || xml.length() > 4_000_000) throw new DataException(Status.unavailable, "treasury_xml_size");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new InputSource(new StringReader(xml)));
            var rows = document.getElementsByTagNameNS(META_NS, "properties");
            if (rows.getLength() == 0) throw new DataException(Status.unavailable, "treasury_missing_rows");
            List<Observation> result = new ArrayList<>();
            Set<LocalDate> dates = new HashSet<>();
            for (int i = 0; i < rows.getLength(); i++) {
                var row = (Element) rows.item(i);
                var date = LocalDateTime.parse(text(row, "NEW_DATE")).toLocalDate();
                if (date.getYear() != requestedYear || !dates.add(date))
                    throw new DataException(Status.unavailable, "treasury_date_mismatch");
                BigDecimal value = new BigDecimal(text(row, "BC_10YEAR"));
                // Treasury quotes are valued at approximately 15:30 New York, not the publication time.
                result.add(new Observation(date, date.atTime(15, 30).atZone(NEW_YORK).toOffsetDateTime(), value));
            }
            return List.copyOf(result);
        } catch (DataException e) { throw e; }
        catch (Exception e) { throw new DataException(Status.unavailable, "invalid_treasury_xml"); }
    }

    private static String text(Element row, String name) {
        var fields = row.getElementsByTagNameNS(DATA_NS, name);
        if (fields.getLength() != 1 || "true".equals(((Element) fields.item(0)).getAttributeNS(META_NS, "null")))
            throw new DataException(Status.unavailable, "treasury_missing_" + name);
        return fields.item(0).getTextContent().trim();
    }

    private record CachedYear(long loadedAt, List<Observation> values) {}
}
