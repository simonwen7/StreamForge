package com.streamforge.api.analytics;

import com.streamforge.api.device.DeviceIds;
import com.streamforge.api.error.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class AnalyticsRequests {

    static final Duration SUMMARY_MAX = Duration.ofDays(366);
    static final Duration HOUR_SERIES_MAX = Duration.ofDays(31);
    static final Duration DAY_SERIES_MAX = Duration.ofDays(366);
    static final Duration READINGS_MAX = Duration.ofDays(7);
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})$");

    private AnalyticsRequests() {
    }

    static void rejectUnknown(HttpServletRequest request, Set<String> allowed) {
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name) || request.getParameterValues(name).length != 1) {
                throw invalid("query", "Unexpected query parameter.");
            }
        }
    }

    static Instant timestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, field + " is required");
        }
        if (!TIMESTAMP.matcher(value).matches()) {
            throw invalid(field, field + " must be an RFC 3339 timestamp with an explicit offset");
        }
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw invalid(field, field + " must be an RFC 3339 timestamp with an explicit offset");
        }
    }

    static Interval interval(String fromRaw, String toRaw, Duration maximum) {
        Instant from = timestamp(fromRaw, "from");
        Instant to = timestamp(toRaw, "to");
        if (!from.isBefore(to)) {
            throw invalid("from", "from must be before to");
        }
        if (Duration.between(from, to).compareTo(maximum) > 0) {
            throw invalid("to", "The requested interval exceeds the maximum span.");
        }
        return new Interval(from, to);
    }

    static Granularity granularity(String raw) {
        if ("hour".equals(raw)) {
            return Granularity.HOUR;
        }
        if ("day".equals(raw)) {
            return Granularity.DAY;
        }
        throw invalid("granularity", "granularity must be hour or day");
    }

    static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw invalid("limit", "limit must be an integer from 1 through 500");
        }
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw invalid("limit", "limit must be an integer from 1 through 500");
        }
        return parsed;
    }

    static void requireDeviceId(String deviceId) {
        if (!DeviceIds.isValid(deviceId)) {
            throw invalid("deviceId", "deviceId format is invalid");
        }
    }

    private static InvalidRequestException invalid(String field, String message) {
        return new InvalidRequestException(List.of(new InvalidRequestException.FieldError(field, message)));
    }

    enum Granularity {
        HOUR(HOUR_SERIES_MAX),
        DAY(DAY_SERIES_MAX);

        private final Duration maximum;

        Granularity(Duration maximum) {
            this.maximum = maximum;
        }

        Duration maximum() {
            return maximum;
        }
    }

    record Interval(Instant from, Instant to) {
    }
}
