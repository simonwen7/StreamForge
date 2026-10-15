package com.streamforge.api.analytics;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Complete UTC calendar days intersected by a half-open instant interval.
 * An exact UTC midnight {@code to} does not include the day that starts there.
 */
final class UtcDays {

    private UtcDays() {
    }

    static Instant startOfDay(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant exclusiveEnd(Instant to) {
        ZonedDateTime zoned = to.atZone(ZoneOffset.UTC);
        if (zoned.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return zoned.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return zoned.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant nextDay(Instant dayStart) {
        return dayStart.plus(1, ChronoUnit.DAYS);
    }
}
