package com.streamforge.api.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AnalyticsRepository {

    static final String HOUR_BUCKET =
            "(date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')";
    static final String DAY_BUCKET =
            "(date_trunc('day', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')";

    private final JdbcClient jdbcClient;

    AnalyticsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean deviceExists(String deviceId) {
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM streamforge.devices WHERE device_id = :deviceId
                        """)
                .param("deviceId", deviceId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    Aggregate aggregate(String deviceId, Instant from, Instant to) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) AS reading_count,
                               MIN(temperature_c) AS temperature_min,
                               MAX(temperature_c) AS temperature_max,
                               ROUND(AVG(temperature_c), 3) AS temperature_avg,
                               ROUND(AVG(humidity_pct), 3) AS humidity_avg,
                               ROUND(AVG(battery_pct), 3) AS battery_avg
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                          AND occurred_at >= :from
                          AND occurred_at < :to
                        """)
                .param("deviceId", deviceId)
                .param("from", offset(from))
                .param("to", offset(to))
                .query((resultSet, rowNumber) -> new Aggregate(
                        resultSet.getLong("reading_count"),
                        decimal(resultSet, "temperature_min"),
                        decimal(resultSet, "temperature_max"),
                        decimal(resultSet, "temperature_avg"),
                        decimal(resultSet, "humidity_avg"),
                        decimal(resultSet, "battery_avg")))
                .single();
    }

    Optional<AnalyticsResponses.Reading> latestInWindow(String deviceId, Instant from, Instant to) {
        return jdbcClient.sql("""
                        SELECT event_id, occurred_at, received_at, temperature_c, humidity_pct, battery_pct
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                          AND occurred_at >= :from
                          AND occurred_at < :to
                        ORDER BY occurred_at DESC, event_id DESC
                        LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .param("from", offset(from))
                .param("to", offset(to))
                .query(this::mapReading)
                .optional();
    }

    AnalyticsResponses.History history(String deviceId) {
        return jdbcClient.sql("""
                        SELECT MIN(occurred_at) AS earliest_occurred_at,
                               MAX(occurred_at) AS latest_occurred_at,
                               MAX(received_at) AS latest_received_at
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                        """)
                .param("deviceId", deviceId)
                .query((resultSet, rowNumber) -> new AnalyticsResponses.History(
                        instant(resultSet, "earliest_occurred_at"),
                        instant(resultSet, "latest_occurred_at"),
                        instant(resultSet, "latest_received_at")))
                .single();
    }

    long publicationBacklog(String deviceId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM streamforge.outbox_events outbox
                        JOIN streamforge.telemetry_events event ON event.event_id = outbox.event_id
                        WHERE event.device_id = :deviceId
                          AND outbox.status = 'pending'
                        """)
                .param("deviceId", deviceId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    long processingBacklog(String deviceId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM streamforge.telemetry_events event
                        WHERE event.device_id = :deviceId
                          AND NOT EXISTS (
                              SELECT 1
                              FROM streamforge.processed_events receipt
                              WHERE receipt.event_id = event.event_id
                          )
                        """)
                .param("deviceId", deviceId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    List<AnalyticsResponses.Bucket> series(
            String deviceId,
            Instant from,
            Instant to,
            AnalyticsRequests.Granularity granularity) {
        String bucket = granularity == AnalyticsRequests.Granularity.HOUR ? HOUR_BUCKET : DAY_BUCKET;
        return jdbcClient.sql("""
                        SELECT %s AS bucket_start,
                               COUNT(*) AS reading_count,
                               MIN(temperature_c) AS temperature_min,
                               MAX(temperature_c) AS temperature_max,
                               ROUND(AVG(temperature_c), 3) AS temperature_avg,
                               ROUND(AVG(humidity_pct), 3) AS humidity_avg,
                               ROUND(AVG(battery_pct), 3) AS battery_avg
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                          AND occurred_at >= :from
                          AND occurred_at < :to
                        GROUP BY 1
                        ORDER BY 1
                        """.formatted(bucket))
                .param("deviceId", deviceId)
                .param("from", offset(from))
                .param("to", offset(to))
                .query((resultSet, rowNumber) -> {
                    Instant start = instant(resultSet, "bucket_start");
                    Instant end = granularity == AnalyticsRequests.Granularity.HOUR
                            ? start.plus(1, java.time.temporal.ChronoUnit.HOURS)
                            : start.plus(1, java.time.temporal.ChronoUnit.DAYS);
                    return new AnalyticsResponses.Bucket(
                            start,
                            end,
                            resultSet.getLong("reading_count"),
                            decimal(resultSet, "temperature_min"),
                            decimal(resultSet, "temperature_max"),
                            decimal(resultSet, "temperature_avg"),
                            decimal(resultSet, "humidity_avg"),
                            decimal(resultSet, "battery_avg"));
                })
                .list();
    }

    List<AnalyticsResponses.Reading> readings(
            String deviceId,
            Instant from,
            Instant to,
            ReadingCursor.Position cursor,
            int limit) {
        if (cursor == null) {
            return jdbcClient.sql("""
                            SELECT event_id, occurred_at, received_at, temperature_c, humidity_pct, battery_pct
                            FROM streamforge.telemetry_events
                            WHERE device_id = :deviceId
                              AND occurred_at >= :from
                              AND occurred_at < :to
                            ORDER BY occurred_at DESC, event_id DESC
                            LIMIT :limit
                            """)
                    .param("deviceId", deviceId)
                    .param("from", offset(from))
                    .param("to", offset(to))
                    .param("limit", limit)
                    .query(this::mapReading)
                    .list();
        }
        return jdbcClient.sql("""
                        SELECT event_id, occurred_at, received_at, temperature_c, humidity_pct, battery_pct
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                          AND occurred_at >= :from
                          AND occurred_at < :to
                          AND (occurred_at, event_id) < (:cursorOccurred, :cursorEventId)
                        ORDER BY occurred_at DESC, event_id DESC
                        LIMIT :limit
                        """)
                .param("deviceId", deviceId)
                .param("from", offset(from))
                .param("to", offset(to))
                .param("cursorOccurred", offset(cursor.occurredAt()))
                .param("cursorEventId", cursor.eventId())
                .param("limit", limit)
                .query(this::mapReading)
                .list();
    }

    private AnalyticsResponses.Reading mapReading(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AnalyticsResponses.Reading(
                resultSet.getObject("event_id", UUID.class),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "received_at"),
                decimal(resultSet, "temperature_c"),
                decimal(resultSet, "humidity_pct"),
                decimal(resultSet, "battery_pct"));
    }

    private static BigDecimal decimal(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getBigDecimal(column);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Aggregate(
            long readingCount,
            BigDecimal temperatureMin,
            BigDecimal temperatureMax,
            BigDecimal temperatureAvg,
            BigDecimal humidityAvg,
            BigDecimal batteryAvg) {
    }
}
