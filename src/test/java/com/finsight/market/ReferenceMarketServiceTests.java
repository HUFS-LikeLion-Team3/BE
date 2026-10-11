package com.finsight.market;

import com.finsight.market.entity.MarketTarget;
import com.finsight.market.service.ReferenceMarketService;
import com.finsight.market.service.ReferenceMarketProvider.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceMarketServiceTests {
    MarketTarget target() {
        MarketTarget target = new MarketTarget();
        ReflectionTestUtils.setField(target, "unit", "%");
        return target;
    }

    @Test void computesChangesAndPreservesMarketTimestamp() {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-01T20:00:00-04:00");
        var result = new ReferenceMarketService(t -> new Snapshot(Status.ready, LocalDate.of(2026, 10, 1),
                at, new BigDecimal("4.25"), new BigDecimal("4.20"), "official", null, null)).get(target());
        assertEquals("ready", result.status());
        assertEquals(at, result.asOfAt());
        assertEquals(new BigDecimal("0.05"), result.changeValue());
        assertEquals(new BigDecimal("1.190476"), result.changePercent());
    }

    @Test void zeroPreviousCloseHasNoPercentage() {
        var result = new ReferenceMarketService(t -> new Snapshot(Status.ready, LocalDate.now(),
                OffsetDateTime.now(), BigDecimal.ONE, BigDecimal.ZERO, "official", null, null)).get(target());
        assertEquals(BigDecimal.ONE, result.changeValue());
        assertNull(result.changePercent());
    }

    @Test void unconfirmedAndUnavailableSnapshotsNeverExposeValues() {
        for (Status status : new Status[]{Status.pending, Status.unavailable}) {
            var result = new ReferenceMarketService(t -> new Snapshot(status, LocalDate.now(),
                    OffsetDateTime.now(), BigDecimal.ONE, BigDecimal.TEN, "official", null, "waiting")).get(target());
            assertEquals(status.name(), result.status());
            assertNull(result.sessionDate());
            assertNull(result.asOfAt());
            assertNull(result.value());
            assertNull(result.previousCloseValue());
            assertNull(result.changeValue());
            assertNull(result.changePercent());
        }
    }

    @Test void incompleteReadySnapshotIsUnavailable() {
        var result = new ReferenceMarketService(t -> new Snapshot(Status.ready, LocalDate.now(),
                OffsetDateTime.now(), BigDecimal.ONE, null, "official", null, null)).get(target());
        assertEquals("unavailable", result.status());
        assertNull(result.value());
    }
}
