package com.streamforge.api.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSchemaUpgradeTests {

    private static final String IMAGE =
            "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";

    @Test
    void v5PreservesAcceptedHistoryAndDoesNotRebuildIt() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            migrate(postgres, "4");
            JdbcTemplate jdbc = jdbc(postgres);
            assertThat(checksum(jdbc, "1")).isEqualTo(595439424);
            assertThat(checksum(jdbc, "2")).isEqualTo(501121659);
            assertThat(checksum(jdbc, "3")).isEqualTo(39366715);
            assertThat(checksum(jdbc, "4")).isEqualTo(-1580136198);

            UUID eventId = UUID.randomUUID();
            jdbc.update("INSERT INTO streamforge.devices (device_id) VALUES ('history-device')");
            jdbc.update("""
                    INSERT INTO streamforge.telemetry_events (
                        event_id, device_id, event_type, schema_version, occurred_at,
                        temperature_c, humidity_pct, battery_pct, canonical_hash
                    ) VALUES (?::uuid, 'history-device', 'telemetry.reading', 1, '2026-01-01T00:00:00Z',
                              21.7, 48.2, 87, decode(repeat('cd', 32), 'hex'))
                    """, eventId.toString());
            jdbc.update(
                    "INSERT INTO streamforge.outbox_events (event_id, status) VALUES (?::uuid, 'pending')",
                    eventId.toString());
            jdbc.update(
                    "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                    eventId.toString());

            migrate(postgres, null);
            assertThat(checksum(jdbc, "1")).isEqualTo(595439424);
            assertThat(checksum(jdbc, "2")).isEqualTo(501121659);
            assertThat(checksum(jdbc, "3")).isEqualTo(39366715);
            assertThat(checksum(jdbc, "4")).isEqualTo(-1580136198);
            assertThat(checksum(jdbc, "5")).isEqualTo(-1671911038);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.telemetry_events", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.outbox_events", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.processed_events", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.device_hour_stats", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.analytics_rebuild_runs", Integer.class)).isZero();
        }
    }

    private static void migrate(PostgreSQLContainer postgres, String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas("streamforge")
                .defaultSchema("streamforge");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static int checksum(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject(
                "SELECT checksum FROM streamforge.flyway_schema_history WHERE version = ?",
                Integer.class,
                version);
    }
}
