package com.streamforge.api.telemetry;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.password=testcontainers-managed")
@Import(PostgresTestcontainersConfiguration.class)
@AutoConfigureMockMvc
class IngestionIntegrationTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";
    private static final String WRONG_KEY = "wrong-key-0123456789abcdef-not-real";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deviceLifecycleAndBoundedListing() throws Exception {
        String prefix = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createDevice(prefix + "a");
        createDevice(prefix + "b");
        createDevice(prefix + "c");

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + prefix + "a\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"-bad\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/devices/" + prefix + "b")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(prefix + "b"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/v1/devices/missing-device-404")
                        .header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"));

        mockMvc.perform(get("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("limit", "2")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.devices.length()").value(2));

        mockMvc.perform(get("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void acceptsNestedEventAndRejectsInvalidContracts() throws Exception {
        String deviceId = deviceId();
        createDevice(deviceId);
        UUID eventId = UUID.randomUUID();
        String occurredAt = "2026-01-01T00:00:00Z";
        MvcResult created = postReading(deviceId, reading(eventId, deviceId, occurredAt, "21.7", "48.2", "87.0"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptance").value("ACCEPTED"))
                .andExpect(jsonPath("$.publication").value("PENDING"))
                .andExpect(jsonPath("$.processing").value("NOT_STARTED"))
                .andExpect(jsonPath("$.processedAt").doesNotExist())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andReturn();
        String receivedAt = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.receivedAt");

        postReading(deviceId, reading(eventId, deviceId, occurredAt, "21.70", "48.200", "87.000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").value(receivedAt))
                .andExpect(jsonPath("$.publication").value("PENDING"));

        postReading(deviceId, reading(eventId, deviceId, "2025-12-31T20:00:00-04:00", "21.7", "48.2", "87"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").value(receivedAt));

        postReading(deviceId, reading(eventId, deviceId, occurredAt, "22.0", "48.2", "87.0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(content().string(not(containsString("ACCEPTED"))));

        assertThat(count("telemetry_events", eventId)).isEqualTo(1);
        assertThat(count("outbox_events", eventId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/telemetry/readings/" + eventId)
                        .header("Authorization", "Bearer " + INGESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").value(receivedAt))
                .andExpect(jsonPath("$.processing").value("NOT_STARTED"));

        String flat = "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + occurredAt + "\","
                + "\"temperatureC\":21.7,\"humidityPct\":48.2,\"batteryPct\":87.0}";
        postReading(deviceId, flat).andExpect(status().isBadRequest());

        String unexpectedTopLevel = reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0");
        unexpectedTopLevel = unexpectedTopLevel.substring(0, unexpectedTopLevel.length() - 1) + ",\"unexpected\":true}";
        postReading(deviceId, unexpectedTopLevel).andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0").replace(
                "\"batteryPct\":87.0", "\"batteryPct\":87.0,\"unexpected\":1")).andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0").replace(
                "\"eventType\":\"telemetry.reading\"", "\"eventType\":\"other\"")).andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0").replace(
                "\"schemaVersion\":1", "\"schemaVersion\":2")).andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0").replace(
                "\"schemaVersion\":1", "\"schemaVersion\":1.0")).andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7", "48.2", "87.0").replace(
                "\"schemaVersion\":1", "\"schemaVersion\":\"1\"")).andExpect(status().isBadRequest());
        postReading(deviceId, "{\"eventId\":\"not-a-uuid\"}").andExpect(status().isBadRequest());
        postReading(deviceId, "{not-json").andExpect(status().isBadRequest());
        postReading(deviceId, reading(new UUID(0, 0), deviceId, occurredAt, "21.7", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, "2026-01-01T00:00:00", "21.7", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, "2026-01-01T00:00:00.1234567Z", "21.7", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        String future = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString();
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, future, "21.7", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "21.7001", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "126", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "-40", "0", "100"))
                .andExpect(status().isAccepted());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "125", "100", "0"))
                .andExpect(status().isAccepted());
        postReading(deviceId, reading(UUID.randomUUID(), deviceId, occurredAt, "null", "48.2", "87.0"))
                .andExpect(status().isBadRequest());
        postReading(deviceId, reading(UUID.randomUUID(), "missing-device-404", occurredAt, "21.7", "48.2", "87"))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.telemetry_events WHERE device_id = 'missing-device-404'",
                Integer.class)).isZero();
    }

    @Test
    void simultaneousDuplicatesAndConflictsLeaveOneEventAndOneOutbox() throws Exception {
        String deviceId = deviceId();
        createDevice(deviceId);
        UUID duplicateId = UUID.randomUUID();
        String duplicateBody = reading(duplicateId, deviceId, "2026-02-01T00:00:00Z", "10.0", "20.0", "30.0");
        List<Attempt> duplicateAttempts = parallelBodies(duplicateBody, duplicateBody);
        assertThat(duplicateAttempts).extracting(Attempt::status).containsExactlyInAnyOrder(202, 200);
        List<String> receivedAts = duplicateAttempts.stream()
                .map(attempt -> com.jayway.jsonpath.JsonPath.read(attempt.body(), "$.receivedAt").toString())
                .toList();
        assertThat(receivedAts).hasSize(2);
        assertThat(receivedAts.get(0)).isNotBlank().isEqualTo(receivedAts.get(1));
        assertThat(count("telemetry_events", duplicateId)).isEqualTo(1);
        assertThat(count("outbox_events", duplicateId)).isEqualTo(1);
        String receivedAt = jdbcTemplate.queryForObject(
                "SELECT received_at FROM streamforge.telemetry_events WHERE event_id = ?::uuid",
                String.class,
                duplicateId.toString());
        assertThat(receivedAt).isNotBlank();

        UUID conflictId = UUID.randomUUID();
        String first = reading(conflictId, deviceId, "2026-02-01T00:00:01Z", "10.0", "20.0", "30.0");
        String second = reading(conflictId, deviceId, "2026-02-01T00:00:01Z", "11.0", "20.0", "30.0");
        assertThat(parallelBodies(first, second)).extracting(Attempt::status).containsExactlyInAnyOrder(202, 409);
        assertThat(count("telemetry_events", conflictId)).isEqualTo(1);
        assertThat(count("outbox_events", conflictId)).isEqualTo(1);
    }

    @Test
    void databaseConstraintsRejectInvalidRows() {
        String deviceId = deviceId();
        jdbcTemplate.update("INSERT INTO streamforge.devices (device_id) VALUES (?)", deviceId);
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> insertEvent(eventId, deviceId, new BigDecimal("21.7001")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("telemetry_events", eventId)).isZero();
        assertThatThrownBy(() -> insertEvent(new UUID(0, 0), deviceId, new BigDecimal("21.7")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO streamforge.devices (device_id) VALUES ('-bad')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID accepted = UUID.randomUUID();
        insertEvent(accepted, deviceId, new BigDecimal("21.7"));
        jdbcTemplate.update("INSERT INTO streamforge.outbox_events (event_id) VALUES (?::uuid)", accepted.toString());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO streamforge.outbox_events (event_id) VALUES (?::uuid)", accepted.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("outbox_events", accepted)).isEqualTo(1);

        jdbcTemplate.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                accepted.toString());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                accepted.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("processed_events", accepted)).isEqualTo(1);
    }

    @Test
    void migrationPreservesV1AndCreatesIngestionObjects() {
        Integer checksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM streamforge.flyway_schema_history WHERE version = '1'",
                Integer.class);
        assertThat(checksum).isEqualTo(595439424);
        Integer versionTwo = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class);
        assertThat(versionTwo).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT notes FROM streamforge.schema_info WHERE id = 1", String.class)).contains("deferred until M3");
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'streamforge'",
                String.class);
        assertThat(tables).contains(
                "devices", "telemetry_events", "outbox_events", "processed_events",
                "schema_info", "flyway_schema_history");
        Integer versionThree = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.flyway_schema_history WHERE version = '3' AND success = true",
                Integer.class);
        assertThat(versionThree).isEqualTo(1);
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'streamforge'",
                String.class);
        assertThat(indexes).contains(
                "telemetry_events_device_occurred_idx",
                "outbox_events_unpublished_idx",
                "outbox_events_claim_idx");
    }

    @Test
    void duplicateJsonKeysAreRejectedBeforeAcceptance() throws Exception {
        String deviceId = deviceId();
        createDevice(deviceId);
        UUID eventId = UUID.randomUUID();
        String duplicateMeasurement = reading(eventId, deviceId, "2026-01-01T00:00:00Z", "10.0", "48.2", "87.0")
                .replace("\"temperatureC\":10.0", "\"temperatureC\":10.0,\"temperatureC\":80.0");
        postReading(deviceId, duplicateMeasurement)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request"))
                .andExpect(jsonPath("$.detail").value("Request body contains a duplicate JSON key."));
        String duplicateEventId = "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"measurements\":{\"temperatureC\":21.7,\"humidityPct\":48.2,\"batteryPct\":87.0}}";
        postReading(deviceId, duplicateEventId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body contains a duplicate JSON key."));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.telemetry_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString())).isZero();
    }

    @Test
    void failedOutboxIsVisibleWithoutChangingAcceptance() throws Exception {
        String deviceId = deviceId();
        createDevice(deviceId);
        UUID eventId = UUID.randomUUID();
        postReading(deviceId, reading(eventId, deviceId, "2026-01-01T00:00:00Z", "21.7", "48.2", "87.0"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publication").value("PENDING"));
        jdbcTemplate.update("""
                UPDATE streamforge.outbox_events
                SET status = 'failed', last_error_code = 'CONTRACT_ENCODING'
                WHERE event_id = ?::uuid AND status = 'pending' AND published_at IS NULL
                """, eventId.toString());
        mockMvc.perform(get("/api/v1/telemetry/readings/" + eventId)
                        .header("Authorization", "Bearer " + INGESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publication").value("FAILED"))
                .andExpect(jsonPath("$.processing").value("NOT_STARTED"))
                .andExpect(jsonPath("$.acceptance").value("ACCEPTED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.telemetry_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString())).isEqualTo(1);
    }

    @Test
    void authorizationBoundaries() throws Exception {
        String deviceId = deviceId();
        mockMvc.perform(post("/api/v1/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(ADMIN))));

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + WRONG_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(WRONG_KEY))));

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Token " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString(INGESTION))));

        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/missing").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/missing"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
        assertThat(countDevices(deviceId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions postReading(String deviceId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/telemetry/readings")
                .header("Authorization", "Bearer " + INGESTION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void createDevice(String deviceId) throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(deviceId));
    }

    private List<Attempt> parallelBodies(String firstBody, String secondBody) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> first = pool.submit(call(barrier, firstBody));
            Future<Attempt> second = pool.submit(call(barrier, secondBody));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Attempt> call(CyclicBarrier barrier, String body) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(post("/api/v1/telemetry/readings")
                            .header("Authorization", "Bearer " + INGESTION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            return new Attempt(result.getResponse().getStatus(), result.getResponse().getContentAsString());
        };
    }

    private record Attempt(int status, String body) {
    }

    private void insertEvent(UUID eventId, String deviceId, BigDecimal temperature) {
        jdbcTemplate.update("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash
                ) VALUES (?::uuid, ?, 'telemetry.reading', 1, '2026-01-01T00:00:00Z', ?, 1, 1, decode(repeat('ab', 32), 'hex'))
                """, eventId.toString(), deviceId, temperature);
    }

    private int count(String table, UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge." + table + " WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
        return count == null ? 0 : count;
    }

    private int countDevices(String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.devices WHERE device_id = ?",
                Integer.class,
                deviceId);
        return count == null ? 0 : count;
    }

    private static String deviceId() {
        return "d" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String reading(
            UUID eventId,
            String deviceId,
            String occurredAt,
            String temperature,
            String humidity,
            String battery) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + occurredAt + "\","
                + "\"measurements\":{"
                + "\"temperatureC\":" + temperature + ","
                + "\"humidityPct\":" + humidity + ","
                + "\"batteryPct\":" + battery
                + "}}";
    }
}
