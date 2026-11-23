package com.finsight.market.data;

import com.finsight.market.entity.MarketTarget;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Published daily valuations for markets without an exchange regular close. */
public interface DailyObservationProvider {
    boolean supports(MarketTarget target);
    List<Observation> observations(MarketTarget target, LocalDate through, int days);
    String sourceName();
    String sourceUrl();
    record Observation(LocalDate sessionDate, OffsetDateTime asOfAt, BigDecimal value) {}
}
