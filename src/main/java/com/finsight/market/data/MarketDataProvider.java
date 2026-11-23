package com.finsight.market.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Vendor boundary shared by pre-submission reference values and outcome collection. */
public interface MarketDataProvider {
    EndOfDay latestEndOfDay(String symbol, String calendarCode);
    EndOfDay endOfDay(String symbol, String calendarCode, LocalDate date);
    Session session(String calendarCode, ZoneId timezone, LocalDate date);
    String sourceName();
    String sourceUrl();

    record EndOfDay(LocalDate date, BigDecimal value) {}
    /** Empty hours represent an explicitly closed day, not an unknown schedule. */
    record Session(LocalDate date, ZoneId timezone, LocalTime open, LocalTime close) {
        public boolean isClosed() { return open == null && close == null; }
    }

    enum Status { ready, pending, unavailable }

    final class DataException extends RuntimeException {
        private final Status status;
        public DataException(Status status) {
            this(status, "unspecified");
        }
        public DataException(Status status, String diagnosticCode) {
            super("Market data is " + status + " (" + diagnosticCode + ")");
            this.status = status;
        }
        public Status status() { return status; }
    }
}
