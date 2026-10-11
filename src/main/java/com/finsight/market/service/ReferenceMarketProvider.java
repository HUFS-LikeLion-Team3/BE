package com.finsight.market.service;

import com.finsight.market.entity.MarketTarget;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Provides the latest confirmed regular close and its immediately preceding regular close.
 * Implementations must use the target calendar/timezone and exclude intraday/provisional data.
 * asOfAt is the market value timestamp, never the collection timestamp.
 */
public interface ReferenceMarketProvider {
    Snapshot latestRegularCloses(MarketTarget target);

    enum Status { ready, pending, unavailable }

    record Snapshot(Status status, LocalDate sessionDate, OffsetDateTime asOfAt,
                    BigDecimal value, BigDecimal previousCloseValue,
                    String dataSourceName, String dataSourceUrl, String unavailableReason) {}
}
