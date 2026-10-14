package com.streamforge.api.device;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class DeviceRepository {

    private final JdbcClient jdbcClient;

    public DeviceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<DeviceResponse> insertIfAbsent(String deviceId) {
        return jdbcClient.sql("""
                        INSERT INTO streamforge.devices (device_id)
                        VALUES (:deviceId)
                        ON CONFLICT (device_id) DO NOTHING
                        RETURNING device_id, created_at
                        """)
                .param("deviceId", deviceId)
                .query(this::map)
                .optional();
    }

    public Optional<DeviceResponse> find(String deviceId) {
        return jdbcClient.sql("""
                        SELECT device_id, created_at
                        FROM streamforge.devices
                        WHERE device_id = :deviceId
                        """)
                .param("deviceId", deviceId)
                .query(this::map)
                .optional();
    }

    public boolean exists(String deviceId) {
        return find(deviceId).isPresent();
    }

    public List<DeviceResponse> list(int limit, int offset) {
        return jdbcClient.sql("""
                        SELECT device_id, created_at
                        FROM streamforge.devices
                        ORDER BY created_at ASC, device_id ASC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::map)
                .list();
    }

    private DeviceResponse map(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
        return new DeviceResponse(resultSet.getString("device_id"), createdAt.toInstant());
    }
}
