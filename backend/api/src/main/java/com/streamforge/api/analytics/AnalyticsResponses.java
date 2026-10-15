package com.streamforge.api.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AnalyticsResponses {

    private AnalyticsResponses() {
    }

    record Summary(
            String deviceId,
            Instant from,
            Instant to,
            long readingCount,
            BigDecimal temperatureMin,
            BigDecimal temperatureMax,
            BigDecimal temperatureAvg,
            BigDecimal humidityAvg,
            BigDecimal batteryAvg,
            Reading latest,
            History history,
            long publicationBacklog,
            long processingBacklog,
            boolean windowOpen) {
    }

    record History(Instant earliestOccurredAt, Instant latestOccurredAt, Instant latestReceivedAt) {
    }

    record Reading(
            UUID eventId,
            Instant occurredAt,
            Instant receivedAt,
            BigDecimal temperatureC,
            BigDecimal humidityPct,
            BigDecimal batteryPct) {
    }

    record Series(
            String deviceId,
            Instant from,
            Instant to,
            String granularity,
            boolean windowOpen,
            List<Bucket> buckets) {
    }

    record Bucket(
            Instant bucketStart,
            Instant bucketEnd,
            long readingCount,
            BigDecimal temperatureMin,
            BigDecimal temperatureMax,
            BigDecimal temperatureAvg,
            BigDecimal humidityAvg,
            BigDecimal batteryAvg) {
    }

    record ReadingPage(String deviceId, Instant from, Instant to, List<Reading> readings, String nextCursor) {
    }
}
