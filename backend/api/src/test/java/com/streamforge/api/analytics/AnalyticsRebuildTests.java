package com.streamforge.api.analytics;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.password=testcontainers-managed")
@Import({PostgresTestcontainersConfiguration.class, AnalyticsRebuildTests.ProbeConfiguration.class})
@AutoConfigureMockMvc
class AnalyticsRebuildTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";

    @Autowired
    private AnalyticsRebuild rebuild;

    @Autowired
    private MutableRebuildProbe probe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void resetProbe() {
        probe.delegate = day -> { };
    }

    @Test
    void rebuildsCompleteUtcDaysWithoutDoubleCounting() {
        String device = deviceId();
        insertDevice(device);
        UUID insideRequest = UUID.randomUUID();
        UUID sameDayOutsideRequest = UUID.randomUUID();
        UUID nextDay = UUID.randomUUID();
        insertReading(insideRequest, device, "2026-06-15T11:00:00Z", "21.500");
        insertReading(sameDayOutsideRequest, device, "2026-06-15T23:00:00Z", "22.500");
        insertReading(nextDay, device, "2026-06-16T01:00:00Z", "9");
        jdbcTemplate.update("INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)", insideRequest.toString());

        RebuildResult first = rebuild.start(
                device,
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T12:00:00Z"));
        assertThat(first.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(first.effectiveFrom()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
        assertThat(first.effectiveTo()).isEqualTo(Instant.parse("2026-06-16T00:00:00Z"));
        assertThat(first.chunksCommitted()).isEqualTo(1);
        assertThat(hourCount(device, "2026-06-15T11:00:00Z")).isEqualTo(1);
        assertThat(hourCount(device, "2026-06-15T23:00:00Z")).isEqualTo(1);
        assertThat(hourSum(device, "2026-06-15T11:00:00Z")).isEqualByComparingTo("21.500");
        assertThat(statRows(device)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.telemetry_events WHERE device_id = ?",
                Integer.class,
                device)).isEqualTo(3);

        RebuildResult midnight = rebuild.start(
                device,
                Instant.parse("2026-06-15T22:00:00Z"),
                Instant.parse("2026-06-16T00:00:00Z"));
        assertThat(midnight.effectiveTo()).isEqualTo(Instant.parse("2026-06-16T00:00:00Z"));
        assertThat(midnight.chunksCommitted()).isEqualTo(1);
        assertThat(hourCount(device, "2026-06-16T01:00:00Z")).isZero();

        RebuildResult second = rebuild.start(
                device,
                Instant.parse("2026-06-15T00:00:00Z"),
                Instant.parse("2026-06-16T00:00:00Z"));
        assertThat(second.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(statRows(device)).isEqualTo(2);
        assertThat(hourSum(device, "2026-06-15T11:00:00Z")).isEqualByComparingTo("21.500");

        RebuildResult again = rebuild.resume(second.runId());
        assertThat(again.outcome()).isEqualTo(RebuildResult.Outcome.ALREADY_COMPLETED);
        assertThat(statRows(device)).isEqualTo(2);
    }

    @Test
    void crossDayFailureRollsBackOnlyTheOpenChunkAndResumeFinishes() throws Exception {
        String device = deviceId();
        createDevice(device);
        postReading(device, "2026-08-01T03:00:00Z", "1");
        postReading(device, "2026-08-02T04:00:00Z", "2");
        AtomicReference<Instant> failedDay = new AtomicReference<>(Instant.parse("2026-08-02T00:00:00Z"));
        probe.delegate = day -> {
            if (day.equals(failedDay.get())) {
                throw new IllegalStateException("injected chunk failure");
            }
        };

        RebuildResult failed = rebuild.start(
                device,
                Instant.parse("2026-08-01T22:00:00Z"),
                Instant.parse("2026-08-02T02:00:00Z"));
        assertThat(failed.outcome()).isEqualTo(RebuildResult.Outcome.FAILED);
        assertThat(failed.errorCode()).isEqualTo("CHUNK_FAILED");
        assertThat(failed.chunksCommitted()).isEqualTo(1);
        assertThat(failed.nextChunkStart()).isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(runStatus(failed.runId())).isEqualTo("failed");
        assertThat(hourCount(device, "2026-08-01T03:00:00Z")).isEqualTo(1);
        assertThat(hourCount(device, "2026-08-02T04:00:00Z")).isZero();

        probe.delegate = day -> { };
        RebuildResult resumed = rebuild.resume(failed.runId());
        assertThat(resumed.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(resumed.chunksCommitted()).isEqualTo(2);
        assertThat(runStatus(failed.runId())).isEqualTo("completed");
        assertThat(hourCount(device, "2026-08-02T04:00:00Z")).isEqualTo(1);
        assertThat(hourCount(device, "2026-08-01T03:00:00Z")).isEqualTo(1);
    }

    @Test
    void lateEventIsVisibleToQueriesBeforeTheNextRebuildAndEmptyDaysClearStaleRows() throws Exception {
        String device = deviceId();
        createDevice(device);
        postReading(device, "2026-09-01T05:00:00Z", "4");
        RebuildResult built = rebuild.start(
                device,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(built.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        postReading(device, "2026-09-01T05:30:00Z", "6");
        mockMvc.perform(get("/api/v1/analytics/devices/" + device + "/summary")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingCount").value(2));
        assertThat(hourCount(device, "2026-09-01T05:00:00Z")).isEqualTo(1);

        RebuildResult refreshed = rebuild.start(
                device,
                Instant.parse("2026-09-01T12:00:00Z"),
                Instant.parse("2026-09-01T18:00:00Z"));
        assertThat(refreshed.chunksCommitted()).isEqualTo(1);
        assertThat(hourCount(device, "2026-09-01T05:00:00Z")).isEqualTo(2);
        assertThat(hourSum(device, "2026-09-01T05:00:00Z")).isEqualByComparingTo("10");

        jdbcTemplate.update("""
                DELETE FROM streamforge.outbox_events
                WHERE event_id IN (SELECT event_id FROM streamforge.telemetry_events WHERE device_id = ?)
                """, device);
        jdbcTemplate.update("DELETE FROM streamforge.telemetry_events WHERE device_id = ?", device);
        RebuildResult cleared = rebuild.start(
                device,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(cleared.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(statRows(device)).isZero();
    }

    @Test
    void deviceScopeDoesNotRewriteOtherDevices() {
        String first = deviceId();
        String second = deviceId();
        insertDevice(first);
        insertDevice(second);
        insertReading(UUID.randomUUID(), first, "2026-10-01T01:00:00Z", "1");
        insertReading(UUID.randomUUID(), second, "2026-10-01T01:00:00Z", "8");
        assertThat(rebuild.start(first, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"))
                .outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(statRows(first)).isEqualTo(1);
        assertThat(statRows(second)).isZero();

        assertThat(rebuild.start(null, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"))
                .outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
        assertThat(statRows(second)).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM streamforge.telemetry_events WHERE device_id = ?", first);
        rebuild.start(first, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(statRows(first)).isZero();
        assertThat(hourCount(second, "2026-10-01T01:00:00Z")).isEqualTo(1);
    }

    @Test
    void overlappingRebuildDoesNotAcquireTheLock() throws Exception {
        String device = deviceId();
        insertDevice(device);
        insertReading(UUID.randomUUID(), device, "2026-11-01T02:00:00Z", "3");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        probe.delegate = day -> {
            inside.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("lock hold timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RebuildResult> running = pool.submit(() -> rebuild.start(
                    device,
                    Instant.parse("2026-11-01T00:00:00Z"),
                    Instant.parse("2026-11-02T00:00:00Z")));
            assertThat(inside.await(20, TimeUnit.SECONDS)).isTrue();
            int runsBefore = runCount();
            RebuildResult rejected = rebuild.start(
                    device,
                    Instant.parse("2026-11-01T00:00:00Z"),
                    Instant.parse("2026-11-02T00:00:00Z"));
            assertThat(rejected.outcome()).isEqualTo(RebuildResult.Outcome.LOCK_NOT_ACQUIRED);
            assertThat(runCount()).isEqualTo(runsBefore);
            release.countDown();
            RebuildResult finished = running.get(20, TimeUnit.SECONDS);
            assertThat(finished.outcome()).isEqualTo(RebuildResult.Outcome.COMPLETED);
            assertThat(hourCount(device, "2026-11-01T02:00:00Z")).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void normalContextDoesNotStartARebuild() {
        assertThat(applicationContext.getBeansOfType(AnalyticsRebuildRunner.class)).isEmpty();
    }

    private void createDevice(String deviceId) throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated());
    }

    private void postReading(String deviceId, String occurredAt, String temperature) throws Exception {
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reading(UUID.randomUUID(), deviceId, occurredAt, temperature)))
                .andExpect(status().isAccepted());
    }

    private void insertDevice(String deviceId) {
        jdbcTemplate.update("INSERT INTO streamforge.devices (device_id) VALUES (?)", deviceId);
    }

    private void insertReading(UUID eventId, String deviceId, String occurredAt, String temperature) {
        jdbcTemplate.update("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash
                ) VALUES (?::uuid, ?, 'telemetry.reading', 1, ?::timestamptz, ?::numeric, 1, 1,
                          decode(repeat('ab', 32), 'hex'))
                """, eventId.toString(), deviceId, occurredAt, temperature);
    }

    private int hourCount(String deviceId, String bucketStart) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(reading_count), 0)
                FROM streamforge.device_hour_stats
                WHERE device_id = ? AND bucket_start = ?::timestamptz
                """, Integer.class, deviceId, bucketStart);
        return count == null ? 0 : count;
    }

    private BigDecimal hourSum(String deviceId, String bucketStart) {
        return jdbcTemplate.queryForObject("""
                SELECT temperature_sum
                FROM streamforge.device_hour_stats
                WHERE device_id = ? AND bucket_start = ?::timestamptz
                """, BigDecimal.class, deviceId, bucketStart);
    }

    private int statRows(String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.device_hour_stats WHERE device_id = ?",
                Integer.class,
                deviceId);
        return count == null ? 0 : count;
    }

    private String runStatus(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.analytics_rebuild_runs WHERE run_id = ?::uuid",
                String.class,
                runId.toString());
    }

    private int runCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.analytics_rebuild_runs",
                Integer.class);
        return count == null ? 0 : count;
    }

    private static String deviceId() {
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String reading(UUID eventId, String deviceId, String occurredAt, String temperature) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + occurredAt + "\","
                + "\"measurements\":{"
                + "\"temperatureC\":" + temperature + ",\"humidityPct\":1,\"batteryPct\":1}}";
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        MutableRebuildProbe rebuildChunkProbe() {
            return new MutableRebuildProbe();
        }
    }

    static final class MutableRebuildProbe implements RebuildChunkProbe {
        volatile Consumer<Instant> delegate = day -> { };

        @Override
        public void beforeCommit(Instant dayStart) {
            delegate.accept(dayStart);
        }
    }
}
