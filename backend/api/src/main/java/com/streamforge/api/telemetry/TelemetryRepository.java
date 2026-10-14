package com.streamforge.api.telemetry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TelemetryRepository {

    private final JdbcClient jdbcClient;

    public TelemetryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<StoredEvent> insertIfAbsent(com.streamforge.api.contract.CanonicalReading reading) {
        return jdbcClient.sql("""
                        INSERT INTO streamforge.telemetry_events (
                            event_id, device_id, event_type, schema_version, occurred_at,
                            temperature_c, humidity_pct, battery_pct, canonical_hash
                        ) VALUES (
                            :eventId, :deviceId, :eventType, :schemaVersion, :occurredAt,
                            :temperatureC, :humidityPct, :batteryPct, :canonicalHash
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING event_id, device_id, received_at, canonical_hash
                        """)
                .param("eventId", reading.eventId())
                .param("deviceId", reading.deviceId())
                .param("eventType", reading.eventType())
                .param("schemaVersion", reading.schemaVersion())
                .param("occurredAt", OffsetDateTime.ofInstant(reading.occurredAt(), java.time.ZoneOffset.UTC))
                .param("temperatureC", reading.temperatureC())
                .param("humidityPct", reading.humidityPct())
                .param("batteryPct", reading.batteryPct())
                .param("canonicalHash", reading.canonicalHash())
                .query(this::mapInserted)
                .optional();
    }

    public void insertOutbox(UUID eventId) {
        jdbcClient.sql("""
                        INSERT INTO streamforge.outbox_events (event_id, status)
                        VALUES (:eventId, 'pending')
                        """)
                .param("eventId", eventId)
                .update();
    }

    public Optional<StoredEvent> find(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT e.event_id, e.device_id, e.received_at, e.canonical_hash, o.published_at
                        FROM streamforge.telemetry_events e
                        JOIN streamforge.outbox_events o ON o.event_id = e.event_id
                        WHERE e.event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(this::mapStored)
                .optional();
    }

    private StoredEvent mapInserted(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("device_id"),
                instant(resultSet, "received_at"),
                resultSet.getBytes("canonical_hash"),
                null);
    }

    private StoredEvent mapStored(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("device_id"),
                instant(resultSet, "received_at"),
                resultSet.getBytes("canonical_hash"),
                instant(resultSet, "published_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record StoredEvent(
            UUID eventId,
            String deviceId,
            Instant receivedAt,
            byte[] canonicalHash,
            Instant publishedAt) {
    }
}
