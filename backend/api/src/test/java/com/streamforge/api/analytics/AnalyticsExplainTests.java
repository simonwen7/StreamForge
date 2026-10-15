package com.streamforge.api.analytics;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local measurement of device-scoped plans. Sequential scans are recorded, not failed.
 * This class uses its own Spring context so the rows stay out of the other test databases.
 */
@SpringBootTest(properties = {
        "spring.datasource.password=testcontainers-managed",
        "streamforge.analytics.benchmark=true"
})
@Import(PostgresTestcontainersConfiguration.class)
class AnalyticsExplainTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnalyticsRepository repository;

    @Autowired
    private AnalyticsRebuild rebuild;

    @Test
    void explainsDeviceScopedQueriesAndOneDayRebuild() throws IOException {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");
        String small = "bench-1k";
        String medium = "bench-10k";
        String large = "bench-100k";
        insert(small, 1_000, from);
        insert(medium, 10_000, from);
        insert(large, 100_000, from);

        StringBuilder report = new StringBuilder();
        for (String device : List.of(small, medium, large)) {
            long count = repository.aggregate(device, from, to).readingCount();
            report.append("dataset ").append(device).append(" rows=").append(count).append('\n');
            report.append(explain("summary", summarySql(), device, from, to));
            report.append(explain("hourly-series-24h", seriesSql(AnalyticsRepository.HOUR_BUCKET), device, from, to));
            report.append(explain("hourly-series-7d", seriesSql(AnalyticsRepository.HOUR_BUCKET), device,
                    Instant.parse("2025-12-26T00:00:00Z"), to));
            report.append(explain("daily-series-30d", seriesSql(AnalyticsRepository.DAY_BUCKET), device,
                    Instant.parse("2025-12-03T00:00:00Z"), to));
            report.append(explain("latest", latestSql(), device, from, to));
            report.append(explain("readings-page", readingsSql(), device, from, to));
        }
        long rebuildStart = System.nanoTime();
        RebuildResult result = rebuild.start(large, from, to);
        long rebuildMillis = (System.nanoTime() - rebuildStart) / 1_000_000L;
        Integer derived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.device_hour_stats WHERE device_id = ?",
                Integer.class,
                large);
        report.append("rebuild device=").append(large)
                .append(" outcome=").append(result.outcome())
                .append(" derivedRows=").append(derived)
                .append(" millis=").append(rebuildMillis)
                .append('\n');
        report.append(explain("rebuild-select", rebuildSelectSql(), large, from, to));
        System.out.println(report);
        Files.writeString(Path.of("/tmp/streamforge-m6-explain.txt"), report);
        org.assertj.core.api.Assertions.assertThat(result.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(derived).isEqualTo(24);
    }

    private void insert(String deviceId, int rows, Instant day) {
        jdbcTemplate.update("INSERT INTO streamforge.devices (device_id) VALUES (?)", deviceId);
        jdbcTemplate.batchUpdate("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash
                ) VALUES (?::uuid, ?, 'telemetry.reading', 1, ?::timestamptz, ?, 40, 80,
                          decode(repeat('ab', 32), 'hex'))
                """, batch(deviceId, rows, day));
    }

    private static List<Object[]> batch(String deviceId, int rows, Instant day) {
        List<Object[]> arguments = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            int second = index % 86_400;
            arguments.add(new Object[] {
                    UUID.randomUUID().toString(),
                    deviceId,
                    day.plusSeconds(second).toString(),
                    (index % 165) - 40
            });
        }
        return arguments;
    }

    private String explain(String label, String sql, String deviceId, Instant from, Instant to) {
        long started = System.nanoTime();
        List<String> lines = jdbcTemplate.query(sql, (resultSet, rowNumber) -> resultSet.getString(1),
                deviceId, OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return label + " device=" + deviceId + " millis=" + millis + "\n" + String.join("\n", lines) + "\n";
    }

    private static String summarySql() {
        return """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT COUNT(*) AS reading_count,
                       MIN(temperature_c), MAX(temperature_c),
                       ROUND(AVG(temperature_c), 3)
                FROM streamforge.telemetry_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                """;
    }

    private static String seriesSql(String bucket) {
        return """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT %s AS bucket_start, COUNT(*), MIN(temperature_c), MAX(temperature_c), ROUND(AVG(temperature_c), 3)
                FROM streamforge.telemetry_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY 1
                ORDER BY 1
                """.formatted(bucket);
    }

    private static String latestSql() {
        return """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT event_id FROM streamforge.telemetry_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at DESC, event_id DESC
                LIMIT 1
                """;
    }

    private static String readingsSql() {
        return """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT event_id FROM streamforge.telemetry_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                ORDER BY occurred_at DESC, event_id DESC
                LIMIT 100
                """;
    }

    private static String rebuildSelectSql() {
        return """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT device_id,
                       (date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
                       COUNT(*)
                FROM streamforge.telemetry_events
                WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY device_id, 2
                """;
    }
}
