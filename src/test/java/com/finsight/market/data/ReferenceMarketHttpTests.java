package com.finsight.market.data;

import com.finsight.market.controller.MarketTargetController;
import com.finsight.market.repository.MarketTargetRepository;
import com.finsight.market.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReferenceMarketHttpTests {
    @Test void realAdapterFeedsReadyReferenceResponseWithoutSessionStorage() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.twelvedata.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new TwelveDataClient(builder.build(), "test-key");
        var data = new MarketDataService(client, Clock.fixed(Instant.parse("2026-10-09T22:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15), 14);
        var reference = new ReferenceMarketService(new LiveReferenceMarketProvider(data));
        var repository = mock(MarketTargetRepository.class);
        var target = MarketDataServiceTests.target();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(target, "id", id);
        ReflectionTestUtils.setField(target, "code", "AAPL");
        ReflectionTestUtils.setField(target, "displayName", "Apple");
        ReflectionTestUtils.setField(target, "marketCategory", "us_equity");
        ReflectionTestUtils.setField(target, "targetType", "stock");
        ReflectionTestUtils.setField(target, "directionLabels", Map.of("up", "상승", "down", "하락", "neutral", "큰 방향성 없음"));
        when(repository.findById(id)).thenReturn(Optional.of(target));
        String sessions = "[{\"session_type\":\"regular\",\"open_time\":\"09:30:00\",\"close_time\":\"16:00:00\"}]";
        server.expect(requestTo("https://api.twelvedata.com/exchange_schedule?mic_code=XNAS&date=2026-10-09"))
                .andRespond(withSuccess(TwelveDataClientTests.schedule(sessions), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.twelvedata.com/time_series?symbol=AAPL&exchange=NASDAQ&interval=1day&outputsize=2&order=desc&adjust=none&prepost=false&dp=6"))
                .andRespond(withSuccess(TwelveDataClientTests.eod("2026-10-09", "225"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.twelvedata.com/exchange_schedule?mic_code=XNAS&date=2026-10-08"))
                .andRespond(withSuccess(TwelveDataClientTests.schedule(sessions), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.twelvedata.com/time_series?symbol=AAPL&exchange=NASDAQ&interval=1day&outputsize=1&order=desc&adjust=none&prepost=false&dp=6&start_date=2026-10-08&end_date=2026-10-09"))
                .andRespond(withSuccess(TwelveDataClientTests.eod("2026-10-08", "220"), MediaType.APPLICATION_JSON));
        var mvc = MockMvcBuilders.standaloneSetup(new MarketTargetController(new MarketTargetService(repository, reference))).build();
        mvc.perform(get("/api/v1/market-targets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceMarket.status").value("ready"))
                .andExpect(jsonPath("$.referenceMarket.sessionDate").value("2026-10-09"))
                .andExpect(jsonPath("$.referenceMarket.asOfAt").value("2026-10-09T16:00:00-04:00"))
                .andExpect(jsonPath("$.referenceMarket.value").value(225))
                .andExpect(jsonPath("$.referenceMarket.previousCloseValue").value(220))
                .andExpect(jsonPath("$.referenceMarket.changeValue").value(5))
                .andExpect(jsonPath("$.referenceMarket.changePercent").value(2.272727))
                .andExpect(jsonPath("$.referenceMarket.dataSourceName").value("Twelve Data"))
                .andExpect(jsonPath("$.referenceMarket.unavailableReason").value(org.hamcrest.Matchers.nullValue()));
        server.verify();
        verify(repository).findById(id);
        verifyNoMoreInteractions(repository);
    }
}
