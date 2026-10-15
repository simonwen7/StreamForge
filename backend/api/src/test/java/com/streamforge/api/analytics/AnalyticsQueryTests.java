package com.streamforge.api.analytics;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.password=testcontainers-managed")
@Import(PostgresTestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AnalyticsQueryTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void summaryAndSeriesUseAcceptedEventTime() throws Exception {
        String device = deviceId();
        String other = deviceId();
        createDevice(device);
        createDevice(other);
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        postReading(device, reading(later, device, "2026-06-15T10:45:00Z", "21.705", "40.000", "80"));
        postReading(device, reading(earlier, device, "2026-06-15T10:15:00Z", "21.706", "50.000", "90"));
        postReading(device, reading(newest, device, "2026-06-15T11:15:00Z", "10", "10", "10"));
        postReading(device, reading(UUID.randomUUID(), device, "2026-06-16T01:00:00Z", "30", "30", "30"));
        postReading(other, reading(UUID.randomUUID(), other, "2026-06-15T10:30:00Z", "99", "99", "99"));
        postReading(device, reading(later, device, "2026-06-15T10:45:00Z", "21.705", "40", "80"))
                .andExpect(status().isOk());

        String summary = summary(device, "2026-06-15T10:00:00Z", "2026-06-15T12:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingCount").value(3))
                .andExpect(jsonPath("$.windowOpen").value(false))
                .andExpect(jsonPath("$.latest.eventId").value(newest.toString()))
                .andExpect(jsonPath("$.history.earliestOccurredAt").value("2026-06-15T10:15:00Z"))
                .andExpect(jsonPath("$.history.latestOccurredAt").value("2026-06-16T01:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertThat(number(summary, "$.temperatureMin")).isEqualByComparingTo("10");
        assertThat(number(summary, "$.temperatureMax")).isEqualByComparingTo("21.706");
        assertThat(number(summary, "$.temperatureAvg")).isEqualByComparingTo("17.804");
        assertThat(number(summary, "$.humidityAvg")).isEqualByComparingTo("33.333");
        assertThat(number(summary, "$.batteryAvg")).isEqualByComparingTo("60.000");

        summary(device, "2026-06-15T10:45:00Z", "2026-06-15T11:15:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingCount").value(1))
                .andExpect(jsonPath("$.latest.eventId").value(later.toString()));
        summary(device, "2026-06-15T10:15:00Z", "2026-06-15T10:45:00Z")
                .andExpect(jsonPath("$.readingCount").value(1))
                .andExpect(jsonPath("$.latest.eventId").value(earlier.toString()));

        String series = series(device, "2026-06-15T10:30:00Z", "2026-06-15T11:30:00Z", "hour")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-06-15T10:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].bucketEnd").value("2026-06-15T11:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].readingCount").value(1))
                .andExpect(jsonPath("$.buckets[1].bucketStart").value("2026-06-15T11:00:00Z"))
                .andExpect(jsonPath("$.buckets[1].readingCount").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(number(series, "$.buckets[0].temperatureAvg")).isEqualByComparingTo("21.705");

        series(device, "2026-06-15T00:00:00Z", "2026-06-17T00:00:00Z", "day")
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-06-15T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].bucketEnd").value("2026-06-16T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].readingCount").value(3))
                .andExpect(jsonPath("$.buckets[1].readingCount").value(1));

        summary(other, "2026-06-15T00:00:00Z", "2026-06-16T00:00:00Z")
                .andExpect(jsonPath("$.readingCount").value(1));
    }

    @Test
    void roundingEmptyWindowsLateEventsAndBacklogs() throws Exception {
        String device = deviceId();
        createDevice(device);
        postReading(device, reading(UUID.randomUUID(), device, "2026-03-01T00:00:00Z", "0.001", "1", "1"));
        postReading(device, reading(UUID.randomUUID(), device, "2026-03-01T00:01:00Z", "0.002", "1", "1"));
        postReading(device, reading(UUID.randomUUID(), device, "2026-03-01T01:00:00Z", "-0.001", "2", "2"));
        postReading(device, reading(UUID.randomUUID(), device, "2026-03-01T01:01:00Z", "-0.002", "2", "2"));
        UUID pendingOutside = UUID.randomUUID();
        postReading(device, reading(pendingOutside, device, "2026-04-01T00:00:00Z", "5", "5", "5"));
        UUID failed = UUID.randomUUID();
        postReading(device, reading(failed, device, "2026-03-01T02:00:00Z", "21.705", "3", "3"));
        jdbcTemplate.update(
                "UPDATE streamforge.outbox_events SET status = 'failed' WHERE event_id = ?::uuid",
                failed.toString());
        jdbcTemplate.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                failed.toString());
        jdbcTemplate.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid) ON CONFLICT DO NOTHING",
                failed.toString());

        String positive = summary(device, "2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z")
                .andExpect(jsonPath("$.readingCount").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(number(positive, "$.temperatureAvg")).isEqualByComparingTo("0.002");
        String negative = summary(device, "2026-03-01T01:00:00Z", "2026-03-01T02:00:00Z")
                .andReturn().getResponse().getContentAsString();
        assertThat(number(negative, "$.temperatureAvg")).isEqualByComparingTo("-0.002");

        summary(device, "2026-03-02T00:00:00Z", "2026-03-03T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingCount").value(0))
                .andExpect(jsonPath("$.temperatureAvg").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.temperatureMin").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.latest").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history.latestOccurredAt").value("2026-04-01T00:00:00Z"))
                .andExpect(jsonPath("$.publicationBacklog").value(5))
                .andExpect(jsonPath("$.processingBacklog").value(5));

        series(device, "2026-03-02T00:00:00Z", "2026-03-03T00:00:00Z", "hour")
                .andExpect(jsonPath("$.buckets.length()").value(0));

        String before = summary(device, "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z")
                .andReturn().getResponse().getContentAsString();
        assertThat(number(before, "$.readingCount")).isEqualByComparingTo("0");
        postReading(device, reading(UUID.randomUUID(), device, "2026-05-01T12:00:00-04:00", "7", "7", "7"));
        summary(device, "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z")
                .andExpect(jsonPath("$.readingCount").value(1))
                .andExpect(jsonPath("$.latest.occurredAt").value("2026-05-01T16:00:00Z"));
        series(device, "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z", "hour")
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-05-01T16:00:00Z"));
    }

    @Test
    void utcBucketIgnoresSessionTimeZone() throws Exception {
        String device = deviceId();
        createDevice(device);
        postReading(device, reading(UUID.randomUUID(), device, "2026-01-01T23:30:00Z", "1", "1", "1"));
        series(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "hour")
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-01-01T23:00:00Z"));
        series(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day")
                .andExpect(jsonPath("$.buckets[0].bucketStart").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.buckets[0].readingCount").value(1));

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SET TIME ZONE 'Pacific/Kiritimati'");
            try (ResultSet resultSet = connection.createStatement().executeQuery("""
                    SELECT (date_trunc('hour', timestamptz '2026-01-01 23:30:00+00' AT TIME ZONE 'UTC')
                            AT TIME ZONE 'UTC')
                    """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getObject(1, java.time.OffsetDateTime.class).toInstant())
                        .isEqualTo(Instant.parse("2026-01-01T23:00:00Z"));
            } finally {
                connection.createStatement().execute("SET TIME ZONE 'UTC'");
            }
        }
    }

    @Test
    void readingsUseDescendingKeysetPagination() throws Exception {
        String device = deviceId();
        createDevice(device);
        UUID low = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID high = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID older = UUID.randomUUID();
        postReading(device, reading(low, device, "2026-07-01T00:00:00Z", "1", "1", "1"));
        postReading(device, reading(high, device, "2026-07-01T00:00:00Z", "2", "2", "2"));
        postReading(device, reading(older, device, "2026-07-01T01:00:00Z", "3", "3", "3"));

        String first = readings(device, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z", "1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].eventId").value(older.toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(first, "$.nextCursor");
        String second = readings(device, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z", "1", cursor)
                .andExpect(jsonPath("$.readings[0].eventId").value(high.toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor2 = com.jayway.jsonpath.JsonPath.read(second, "$.nextCursor");
        String third = readings(device, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z", "1", cursor2)
                .andExpect(jsonPath("$.readings[0].eventId").value(low.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(third, "$.nextCursor")).isNull();

        readings(device, "2026-07-01T00:00:00Z", "2026-07-02T00:00:00Z", null, null)
                .andExpect(jsonPath("$.readings.length()").value(3));
        readings(device, "2026-07-03T00:00:00Z", "2026-07-04T00:00:00Z", "100", null)
                .andExpect(jsonPath("$.readings.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void rejectsInvalidQueriesAndEnforcesAdminAccess() throws Exception {
        String device = deviceId();
        createDevice(device);
        summary(device, "2026-01-02T00:00:00Z", "2026-01-01T00:00:00Z").andExpect(status().isBadRequest());
        summary(device, "2026-01-01T00:00:00", "2026-01-02T00:00:00Z").andExpect(status().isBadRequest());
        summary(device, null, "2026-01-02T00:00:00Z").andExpect(status().isBadRequest());
        summary(device, "2024-01-01T00:00:00Z", "2025-01-01T00:00:01Z").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/analytics/devices/" + device + "/summary")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-02T00:00:00Z")
                        .param("injected", "super-secret-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("query"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("super-secret-value"))));
        series(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "minute").andExpect(status().isBadRequest());
        series(device, "2026-01-01T00:00:00Z", "2026-02-01T00:00:01Z", "hour").andExpect(status().isBadRequest());
        series(device, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "hour").andExpect(status().isOk());
        readings(device, "2026-01-01T00:00:00Z", "2026-01-09T00:00:00Z", "10", null).andExpect(status().isBadRequest());
        readings(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "0", null).andExpect(status().isBadRequest());
        readings(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "501", null).andExpect(status().isBadRequest());
        readings(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "500", null).andExpect(status().isOk());
        readings(device, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "10", "not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("SELECT"))));

        summary("missing-device-404", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/analytics/devices/-bad/summary")
                        .header("Authorization", "Bearer " + ADMIN)
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-02T00:00:00Z"))
                .andExpect(status().isBadRequest());

        for (String path : new String[] {
                "/api/v1/analytics/devices/" + device + "/summary?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z",
                "/api/v1/analytics/devices/" + device + "/series?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&granularity=day",
                "/api/v1/devices/" + device + "/readings?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z"
        }) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + ADMIN)).andExpect(status().isOk());
            mockMvc.perform(get(path).header("Authorization", "Bearer " + INGESTION)).andExpect(status().isForbidden());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }

        String futureTo = java.time.Instant.now().plus(java.time.Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        summary(device, "2026-01-01T00:00:00Z", futureTo).andExpect(jsonPath("$.windowOpen").value(true));
    }

    private ResultActions summary(String device, String from, String to) throws Exception {
        var request = get("/api/v1/analytics/devices/" + device + "/summary")
                .header("Authorization", "Bearer " + ADMIN);
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }
        return mockMvc.perform(request);
    }

    private ResultActions series(String device, String from, String to, String granularity) throws Exception {
        return mockMvc.perform(get("/api/v1/analytics/devices/" + device + "/series")
                .header("Authorization", "Bearer " + ADMIN)
                .param("from", from)
                .param("to", to)
                .param("granularity", granularity));
    }

    private ResultActions readings(String device, String from, String to, String limit, String cursor) throws Exception {
        var request = get("/api/v1/devices/" + device + "/readings")
                .header("Authorization", "Bearer " + ADMIN)
                .param("from", from)
                .param("to", to);
        if (limit != null) {
            request.param("limit", limit);
        }
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private void createDevice(String deviceId) throws Exception {
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions postReading(String device, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/telemetry/readings")
                .header("Authorization", "Bearer " + INGESTION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static BigDecimal number(String json, String path) {
        Object value = com.jayway.jsonpath.JsonPath.read(json, path);
        return new BigDecimal(value.toString());
    }

    private static String deviceId() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
