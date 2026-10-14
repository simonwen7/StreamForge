package com.streamforge.api.telemetry;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.password=testcontainers-managed")
@Import(PostgresTestcontainersConfiguration.class)
@AutoConfigureMockMvc
class IngestionRollbackTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TelemetryRepository telemetryRepository;

    @Test
    void outboxFailureRollsBackTheEventAndDoesNotClaimAcceptance() throws Exception {
        doThrow(new IllegalStateException("forced-outbox-failure"))
                .when(telemetryRepository).insertOutbox(any());
        String deviceId = "r" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated());

        UUID eventId = UUID.randomUUID();
        String body = "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-03-01T00:00:00Z\","
                + "\"measurements\":{\"temperatureC\":21.7,\"humidityPct\":48.2,\"batteryPct\":87.0}}";
        mockMvc.perform(post("/api/v1/telemetry/readings")
                        .header("Authorization", "Bearer " + INGESTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.acceptance").doesNotExist())
                .andExpect(content().string(not(containsString("forced-outbox-failure"))))
                .andExpect(content().string(not(containsString("ACCEPTED"))));

        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.telemetry_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
        Integer outbox = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
        assertThat(events).isZero();
        assertThat(outbox).isZero();
    }
}
