package com.finsight.feedback.service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/** Compares immutable cutoffs at MySQL DATETIME(6) default rounding precision. */
final class SubmissionTimePrecision {
    private SubmissionTimePrecision() {}

    static boolean matches(OffsetDateTime first, OffsetDateTime second) {
        if (first == null || second == null) {
            return false;
        }
        // Do not truncate: .589211844 is persisted as .589212, including
        // carries into the next second. Original snapshots stay untouched.
        return first.toInstant().plusNanos(500).truncatedTo(ChronoUnit.MICROS)
                .equals(second.toInstant().plusNanos(500).truncatedTo(ChronoUnit.MICROS));
    }
}
