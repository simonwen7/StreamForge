package com.streamforge.api.publication;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxSchemaUpgradeTests {

    @Test
    void v4PreservesRowsCommittedBeforeTheClaimColumnsExisted() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(PostgresTestcontainersImage.IMAGE).asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .schemas("streamforge")
                    .defaultSchema("streamforge")
                    .target("3")
                    .load()
                    .migrate();
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(checksum(jdbc, "1")).isEqualTo(595439424);
            assertThat(checksum(jdbc, "2")).isEqualTo(501121659);
            assertThat(checksum(jdbc, "3")).isEqualTo(39366715);

            UUID pending = UUID.randomUUID();
            UUID published = UUID.randomUUID();
            jdbc.update("INSERT INTO streamforge.devices (device_id) VALUES ('upgrade-device')");
            insertEvent(jdbc, pending);
            insertEvent(jdbc, published);
            jdbc.update("INSERT INTO streamforge.outbox_events (event_id, status) VALUES (?::uuid, 'pending')", pending.toString());
            jdbc.update("""
                    INSERT INTO streamforge.outbox_events (event_id, status, published_at)
                    VALUES (?::uuid, 'published', clock_timestamp())
                    """, published.toString());

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .schemas("streamforge")
                    .defaultSchema("streamforge")
                    .load()
                    .migrate();

            assertThat(checksum(jdbc, "1")).isEqualTo(595439424);
            assertThat(checksum(jdbc, "2")).isEqualTo(501121659);
            assertThat(checksum(jdbc, "3")).isEqualTo(39366715);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    String.class,
                    pending.toString())).isEqualTo("pending");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    String.class,
                    published.toString())).isEqualTo("published");
            assertThat(jdbc.queryForObject(
                    "SELECT attempt_count FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    Integer.class,
                    pending.toString())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT claim_token FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    Object.class,
                    published.toString())).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM streamforge.telemetry_events",
                    Integer.class)).isEqualTo(2);
        }
    }

    private static void insertEvent(JdbcTemplate jdbc, UUID eventId) {
        jdbc.update("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash
                ) VALUES (?::uuid, 'upgrade-device', 'telemetry.reading', 1, '2026-01-01T00:00:00Z',
                          21.7, 48.2, 87.0, decode(repeat('ab', 32), 'hex'))
                """, eventId.toString());
    }

    private static int checksum(JdbcTemplate jdbc, String version) {
        return jdbc.queryForObject(
                "SELECT checksum FROM streamforge.flyway_schema_history WHERE version = ?",
                Integer.class,
                version);
    }

    private static final class PostgresTestcontainersImage {
        private static final String IMAGE =
                "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";
    }
}
