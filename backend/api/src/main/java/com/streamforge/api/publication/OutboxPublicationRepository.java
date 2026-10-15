package com.streamforge.api.publication;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxPublicationRepository {

    private final JdbcClient jdbcClient;

    public OutboxPublicationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<PendingReading> findPending(int batchSize) {
        return jdbcClient.sql("""
                        SELECT e.event_id, e.device_id, e.event_type, e.schema_version, e.occurred_at,
                               e.temperature_c, e.humidity_pct, e.battery_pct, e.received_at
                        FROM streamforge.outbox_events o
                        JOIN streamforge.telemetry_events e ON e.event_id = o.event_id
                        WHERE o.status = 'pending'
                        ORDER BY o.created_at
                        LIMIT :batchSize
                        """)
                .param("batchSize", batchSize)
                .query(this::mapPending)
                .list();
    }

    public int countPending() {
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM streamforge.outbox_events WHERE status = 'pending'
                        """)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public int markPublished(UUID eventId) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET status = 'published', published_at = clock_timestamp()
                        WHERE event_id = :eventId AND status = 'pending'
                        """)
                .param("eventId", eventId)
                .update();
    }

    private PendingReading mapPending(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PendingReading(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("device_id"),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant().atOffset(ZoneOffset.UTC).toInstant(),
                resultSet.getBigDecimal("temperature_c"),
                resultSet.getBigDecimal("humidity_pct"),
                resultSet.getBigDecimal("battery_pct"),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant());
    }
}
