package com.streamforge.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProcessingRepository {

    private final JdbcClient jdbcClient;

    public ProcessingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<StoredReading> findTelemetry(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT event_id, device_id, event_type, schema_version, occurred_at,
                               temperature_c, humidity_pct, battery_pct, received_at
                        FROM streamforge.telemetry_events
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(this::mapStored)
                .optional();
    }

    public boolean receiptExists(UUID eventId) {
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM streamforge.processed_events WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int insertReceipt(UUID eventId) {
        return jdbcClient.sql("""
                        INSERT INTO streamforge.processed_events (event_id)
                        VALUES (:eventId)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .update();
    }

    private StoredReading mapStored(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredReading(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("device_id"),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS),
                resultSet.getBigDecimal("temperature_c"),
                resultSet.getBigDecimal("humidity_pct"),
                resultSet.getBigDecimal("battery_pct"),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS));
    }

    record StoredReading(
            UUID eventId,
            String deviceId,
            String eventType,
            int schemaVersion,
            java.time.Instant occurredAt,
            BigDecimal temperatureC,
            BigDecimal humidityPct,
            BigDecimal batteryPct,
            java.time.Instant receivedAt) {
    }
}
