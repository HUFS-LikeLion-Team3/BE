package com.finsight.feedback.service;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionTimePrecisionTest {
    @ParameterizedTest
    @CsvSource({
        "2026-11-23T18:43:32.589211844+09:00,2026-11-23T09:43:32.589212Z",
        "2026-11-23T18:43:32.123456499+09:00,2026-11-23T09:43:32.123456Z",
        "2026-11-23T18:43:32.123456500+09:00,2026-11-23T09:43:32.123457Z",
        "2026-11-23T23:59:59.999999900Z,2026-11-24T00:00:00Z"
    })
    void acceptsOnlyDatabaseRoundingEquivalentInstants(String raw, String stored) {
        assertTrue(SubmissionTimePrecision.matches(OffsetDateTime.parse(raw), OffsetDateTime.parse(stored)));
    }

    @Test
    void rejectsMissingOrDifferentStoredMicrosecond() {
        OffsetDateTime raw = OffsetDateTime.parse("2026-11-23T18:43:32.589211844+09:00");
        assertFalse(SubmissionTimePrecision.matches(raw, raw.plusNanos(1000)));
        assertFalse(SubmissionTimePrecision.matches(raw, raw.plusSeconds(1)));
        assertFalse(SubmissionTimePrecision.matches(raw, null));
        assertFalse(SubmissionTimePrecision.matches(null, raw));
    }
}
