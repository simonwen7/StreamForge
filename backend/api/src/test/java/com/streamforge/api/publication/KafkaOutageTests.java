package com.streamforge.api.publication;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.password=testcontainers-managed",
        "streamforge.publication.enabled=true",
        "streamforge.publication.poll-delay-ms=3600000",
        "streamforge.publication.send-timeout=2s",
        "streamforge.publication.retry-base=30s",
        "spring.kafka.bootstrap-servers=127.0.0.1:9",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"
})
@Import(PostgresTestcontainersConfiguration.class)
@AutoConfigureMockMvc
class KafkaOutageTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher publisher;

    @Test
    void ingestionStaysAcceptedAndPendingWhileKafkaIsUnreachable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        String deviceId = "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reading(first, deviceId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.publication").value("PENDING"))
                .andExpect(jsonPath("$.processing").value("NOT_STARTED"));
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reading(second, deviceId).replace("2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z")))
                .andExpect(status().isAccepted());

        assertThat(publisher.publishPending()).isZero();
        assertRetryable(first);
        assertRetryable(second);
    }

    @Test
    void transientFailureAfterTwentyAttemptsStaysPending() throws Exception {
        String deviceId = "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID eventId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reading(eventId, deviceId)))
                .andExpect(status().isAccepted());
        jdbcTemplate.update(
                "UPDATE streamforge.outbox_events SET attempt_count = 20 WHERE event_id = ?::uuid",
                eventId.toString());

        assertThat(publisher.publishPending()).isZero();
        assertThat(attempts(eventId)).isEqualTo(21);
        assertThat(statusOf(eventId)).isEqualTo("pending");
        assertThat(publishedAt(eventId)).isNull();
        mockMvc.perform(get("/api/v1/telemetry/readings/" + eventId)
                        .header("Authorization", "Bearer " + INGESTION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publication").value("PENDING"));
    }

    private void assertRetryable(UUID eventId) {
        assertThat(statusOf(eventId)).isEqualTo("pending");
        assertThat(publishedAt(eventId)).isNull();
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_token FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Object.class,
                eventId.toString())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at > clock_timestamp() FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Boolean.class,
                eventId.toString())).isTrue();
    }

    private String statusOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                String.class,
                eventId.toString());
    }

    private Object publishedAt(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Object.class,
                eventId.toString());
    }

    private int attempts(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
    }

    private static String reading(UUID eventId, String deviceId) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"measurements\":{\"temperatureC\":21.7,\"humidityPct\":48.2,\"batteryPct\":87.0}}";
    }
}
