package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static com.finsight.market.data.MarketDataProvider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class EcosClientTests {
    final RestClient.Builder builder = RestClient.builder().baseUrl("https://ecos.bok.or.kr");
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final EcosClient client = new EcosClient(builder.build(), "test-key");
    final ZoneId seoul = ZoneId.of("Asia/Seoul");

    static MarketTarget target(String symbol, String unit, String type) {
        var target = new MarketTarget();
        ReflectionTestUtils.setField(target, "calendarCode", "ECOS");
        ReflectionTestUtils.setField(target, "dataSymbol", symbol);
        ReflectionTestUtils.setField(target, "timezone", "Asia/Seoul");
        ReflectionTestUtils.setField(target, "unit", unit);
        ReflectionTestUtils.setField(target, "targetType", type);
        return target;
    }

    static String rows(String stat, String item, String... timeValue) {
        var sb = new StringBuilder();
        for (int i = 0; i < timeValue.length; i += 2) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append("""
                {"STAT_CODE":"%s","ITEM_CODE1":"%s","UNIT_NAME":"x","TIME":"%s","DATA_VALUE":"%s"}
                """.formatted(stat, item, timeValue[i], timeValue[i + 1]));
        }
        return "{\"StatisticSearch\":{\"list_total_count\":" + timeValue.length / 2 + ",\"row\":[" + sb + "]}}";
    }

    @Test void readsPublishedKospiClosesOnlyAndStampsKrxCloseTime() {
        server.expect(requestTo("https://ecos.bok.or.kr/api/StatisticSearch/test-key/json/kr/1/15/802Y001/D/20261110/20261124/0001000"))
                .andRespond(withSuccess(rows("802Y001", "0001000", "20261121", "5010.10", "20261124", "4990.5"), MediaType.APPLICATION_JSON));
        var values = client.observations(target("802Y001/0001000", "point", "index"), LocalDate.of(2026, 11, 24), 14);
        server.verify();
        assertEquals(2, values.size());
        assertEquals(LocalDate.of(2026, 11, 21), values.get(0).sessionDate());
        assertEquals(0, new BigDecimal("4990.5").compareTo(values.get(1).value()));
        assertEquals(LocalDate.of(2026, 11, 24).atTime(15, 30).atZone(seoul).toOffsetDateTime(), values.get(1).asOfAt());
    }

    @Test void readsKtb3yYieldInPercent() {
        server.expect(requestTo("https://ecos.bok.or.kr/api/StatisticSearch/test-key/json/kr/1/2/817Y002/D/20261123/20261124/010200000"))
                .andRespond(withSuccess(rows("817Y002", "010200000", "20261124", "3.736"), MediaType.APPLICATION_JSON));
        var values = client.observations(target("817Y002/010200000", "%", "yield"), LocalDate.of(2026, 11, 24), 1);
        assertEquals(List.of(new DailyObservationProvider.Observation(LocalDate.of(2026, 11, 24),
                LocalDate.of(2026, 11, 24).atTime(15, 30).atZone(seoul).toOffsetDateTime(), new BigDecimal("3.736"))), values);
    }

    @Test void noDataIsEmptyNotInvented() {
        server.expect(requestTo("https://ecos.bok.or.kr/api/StatisticSearch/test-key/json/kr/1/2/802Y001/D/20261121/20261122/0089000"))
                .andRespond(withSuccess("{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"해당하는 데이터가 없습니다.\"}}", MediaType.APPLICATION_JSON));
        assertTrue(client.observations(target("802Y001/0089000", "point", "index"), LocalDate.of(2026, 11, 22), 1).isEmpty());
    }

    @Test void invalidKeyIsUnavailableAndServerErrorIsPending() {
        server.expect(requestTo("https://ecos.bok.or.kr/api/StatisticSearch/test-key/json/kr/1/2/802Y001/D/20261123/20261124/0001000"))
                .andRespond(withSuccess("{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"인증키가 유효하지 않습니다.\"}}", MediaType.APPLICATION_JSON));
        var e = assertThrows(DataException.class, () -> client.observations(target("802Y001/0001000", "point", "index"), LocalDate.of(2026, 11, 24), 1));
        assertEquals(Status.unavailable, e.status());
        var busy = assertThrows(DataException.class, () -> EcosClient.parse(new EcosClient.Response(null,
                new EcosClient.Result("ERROR-602", "과도한 호출")), "802Y001", "0001000", LocalDate.MIN, LocalDate.MAX));
        assertEquals(Status.pending, busy.status());
    }

    @Test void rejectsMismatchedSeriesRowsAndUnknownTargets() {
        server.expect(requestTo("https://ecos.bok.or.kr/api/StatisticSearch/test-key/json/kr/1/2/802Y001/D/20261123/20261124/0001000"))
                .andRespond(withSuccess(rows("802Y001", "0089000", "20261124", "650.1"), MediaType.APPLICATION_JSON));
        var mismatch = assertThrows(DataException.class, () -> client.observations(target("802Y001/0001000", "point", "index"), LocalDate.of(2026, 11, 24), 1));
        assertEquals(Status.unavailable, mismatch.status());
        assertThrows(DataException.class, () -> client.observations(target("999Y999/1", "point", "index"), LocalDate.of(2026, 11, 24), 1));
        assertThrows(DataException.class, () -> client.observations(target("817Y002/010200000", "point", "yield"), LocalDate.of(2026, 11, 24), 1));
        var noKey = new EcosClient(builder.build(), "");
        assertEquals(Status.unavailable, assertThrows(DataException.class,
                () -> noKey.observations(target("802Y001/0001000", "point", "index"), LocalDate.of(2026, 11, 24), 1)).status());
    }
}
