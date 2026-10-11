package com.finsight.learning.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Normalize all learning-session API timestamps to the documented Korea offset. */
final class LearningSessionDateTime {
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private LearningSessionDateTime() {
    }

    static OffsetDateTime toKorea(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KOREA_ZONE).toOffsetDateTime();
    }
}
