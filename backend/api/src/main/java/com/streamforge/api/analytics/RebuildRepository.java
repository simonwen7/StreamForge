package com.streamforge.api.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
class RebuildRepository {

    private final JdbcClient jdbcClient;

    RebuildRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insertRun(
            UUID runId,
            String deviceId,
            Instant requestedFrom,
            Instant requestedTo,
            Instant effectiveFrom,
            Instant effectiveTo) {
        jdbcClient.sql("""
                        INSERT INTO streamforge.analytics_rebuild_runs (
                            run_id, device_id, requested_from, requested_to,
                            effective_from, effective_to, next_chunk_start, status, chunks_committed
                        ) VALUES (
                            :runId, :deviceId, :requestedFrom, :requestedTo,
                            :effectiveFrom, :effectiveTo, :effectiveFrom, 'running', 0
                        )
                        """)
                .param("runId", runId)
                .param("deviceId", deviceId)
                .param("requestedFrom", offset(requestedFrom))
                .param("requestedTo", offset(requestedTo))
                .param("effectiveFrom", offset(effectiveFrom))
                .param("effectiveTo", offset(effectiveTo))
                .update();
    }

    Optional<RebuildRun> find(UUID runId) {
        return jdbcClient.sql("""
                        SELECT run_id, device_id, requested_from, requested_to, effective_from, effective_to,
                               next_chunk_start, status, chunks_committed, error_code
                        FROM streamforge.analytics_rebuild_runs
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query((resultSet, rowNumber) -> new RebuildRun(
                        resultSet.getObject("run_id", UUID.class),
                        resultSet.getString("device_id"),
                        instant(resultSet.getObject("requested_from", OffsetDateTime.class)),
                        instant(resultSet.getObject("requested_to", OffsetDateTime.class)),
                        instant(resultSet.getObject("effective_from", OffsetDateTime.class)),
                        instant(resultSet.getObject("effective_to", OffsetDateTime.class)),
                        instant(resultSet.getObject("next_chunk_start", OffsetDateTime.class)),
                        resultSet.getString("status"),
                        resultSet.getInt("chunks_committed"),
                        resultSet.getString("error_code")))
                .optional();
    }

    int markResumed(UUID runId) {
        return jdbcClient.sql("""
                        UPDATE streamforge.analytics_rebuild_runs
                        SET status = 'running',
                            finished_at = NULL,
                            error_code = NULL,
                            updated_at = clock_timestamp()
                        WHERE run_id = :runId
                          AND status IN ('running', 'failed')
                          AND next_chunk_start < effective_to
                        """)
                .param("runId", runId)
                .update();
    }

    int replaceDay(String deviceId, Instant dayStart, Instant dayEnd) {
        if (deviceId == null) {
            jdbcClient.sql("""
                            DELETE FROM streamforge.device_hour_stats
                            WHERE bucket_start >= :dayStart
                              AND bucket_start < :dayEnd
                            """)
                    .param("dayStart", offset(dayStart))
                    .param("dayEnd", offset(dayEnd))
                    .update();
            return jdbcClient.sql("""
                            INSERT INTO streamforge.device_hour_stats (
                                device_id, bucket_start, reading_count,
                                temperature_min, temperature_max, temperature_sum,
                                humidity_sum, battery_sum, max_source_received_at
                            )
                            SELECT device_id,
                                   (date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
                                   COUNT(*),
                                   MIN(temperature_c),
                                   MAX(temperature_c),
                                   SUM(temperature_c),
                                   SUM(humidity_pct),
                                   SUM(battery_pct),
                                   MAX(received_at)
                            FROM streamforge.telemetry_events
                            WHERE occurred_at >= :dayStart
                              AND occurred_at < :dayEnd
                            GROUP BY device_id, 2
                            """)
                    .param("dayStart", offset(dayStart))
                    .param("dayEnd", offset(dayEnd))
                    .update();
        }
        jdbcClient.sql("""
                        DELETE FROM streamforge.device_hour_stats
                        WHERE device_id = :deviceId
                          AND bucket_start >= :dayStart
                          AND bucket_start < :dayEnd
                        """)
                .param("deviceId", deviceId)
                .param("dayStart", offset(dayStart))
                .param("dayEnd", offset(dayEnd))
                .update();
        return jdbcClient.sql("""
                        INSERT INTO streamforge.device_hour_stats (
                            device_id, bucket_start, reading_count,
                            temperature_min, temperature_max, temperature_sum,
                            humidity_sum, battery_sum, max_source_received_at
                        )
                        SELECT device_id,
                               (date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
                               COUNT(*),
                               MIN(temperature_c),
                               MAX(temperature_c),
                               SUM(temperature_c),
                               SUM(humidity_pct),
                               SUM(battery_pct),
                               MAX(received_at)
                        FROM streamforge.telemetry_events
                        WHERE device_id = :deviceId
                          AND occurred_at >= :dayStart
                          AND occurred_at < :dayEnd
                        GROUP BY device_id, 2
                        """)
                .param("deviceId", deviceId)
                .param("dayStart", offset(dayStart))
                .param("dayEnd", offset(dayEnd))
                .update();
    }

    /**
     * Advances {@code next_chunk_start} only when it still points at this day.
     * The last day also marks the run completed in this same statement.
     */
    int advance(UUID runId, Instant dayStart, Instant dayEnd) {
        return jdbcClient.sql("""
                        UPDATE streamforge.analytics_rebuild_runs
                        SET next_chunk_start = :dayEnd,
                            chunks_committed = chunks_committed + 1,
                            updated_at = clock_timestamp(),
                            status = CASE WHEN :dayEnd = effective_to THEN 'completed' ELSE status END,
                            finished_at = CASE WHEN :dayEnd = effective_to THEN clock_timestamp() ELSE finished_at END
                        WHERE run_id = :runId
                          AND status = 'running'
                          AND next_chunk_start = :dayStart
                        """)
                .param("runId", runId)
                .param("dayStart", offset(dayStart))
                .param("dayEnd", offset(dayEnd))
                .update();
    }

    int markFailed(UUID runId, String errorCode) {
        return jdbcClient.sql("""
                        UPDATE streamforge.analytics_rebuild_runs
                        SET status = 'failed',
                            error_code = :errorCode,
                            finished_at = clock_timestamp(),
                            updated_at = clock_timestamp()
                        WHERE run_id = :runId
                          AND status = 'running'
                        """)
                .param("runId", runId)
                .param("errorCode", errorCode)
                .update();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record RebuildRun(
            UUID runId,
            String deviceId,
            Instant requestedFrom,
            Instant requestedTo,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant nextChunkStart,
            String status,
            int chunksCommitted,
            String errorCode) {
    }
}
