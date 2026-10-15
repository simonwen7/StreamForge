package com.streamforge.api;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import com.streamforge.api.publication.OutboxPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full production context. The password property is a non-secret stand-in so startup
 * fail-fast is satisfied; {@code @ServiceConnection} supplies the disposable database.
 * There is no {@code src/test/resources/application.yml}, because that file would hide
 * the production Flyway settings.
 */
@SpringBootTest(properties = "spring.datasource.password=testcontainers-managed")
@Import(PostgresTestcontainersConfiguration.class)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class StreamForgeApiApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceProperties dataSourceProperties;

    @Autowired
    private FlywayProperties flywayProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextStartsWithPostgreSQL() throws Exception {
        assertThat(dataSource).isNotNull();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
            assertThat(metaData.getURL()).startsWith("jdbc:postgresql:");
            assertThat(metaData.getURL()).contains("/test");
        }
    }

    @Test
    void configurationUsesPostgresAndFlyway() {
        assertThat(dataSourceProperties.determineUrl()).startsWith("jdbc:postgresql:");
        assertThat(dataSourceProperties.determineUrl()).doesNotContain("h2");
        assertThat(flywayProperties.isEnabled()).isTrue();
        assertThat(flywayProperties.getLocations()).contains("classpath:db/migration");
        assertThat(dataSourceProperties.getPassword()).isNotBlank();
    }

    @Test
    void flywayAppliedPlatformMigration() {
        String databaseVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertThat(databaseVersion).contains("PostgreSQL 17.11");

        Integer successCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(successCount).isEqualTo(1);

        Integer checksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM streamforge.flyway_schema_history WHERE version = '1'",
                Integer.class);
        assertThat(checksum).isEqualTo(595439424);

        Integer versionTwoChecksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM streamforge.flyway_schema_history WHERE version = '2'",
                Integer.class);
        assertThat(versionTwoChecksum).isEqualTo(501121659);

        Integer versionThree = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.flyway_schema_history WHERE version = '3' AND success = true",
                Integer.class);
        assertThat(versionThree).isEqualTo(1);
        Integer versionFour = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.flyway_schema_history WHERE version = '4' AND success = true",
                Integer.class);
        assertThat(versionFour).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'streamforge' AND table_name = 'outbox_events' AND column_name = 'claim_token'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.processed_events", Integer.class)).isZero();

        String notes = jdbcTemplate.queryForObject(
                "SELECT notes FROM streamforge.schema_info WHERE id = 1",
                String.class);
        assertThat(notes).contains("deferred until M3");
    }

    @Test
    void actuatorHealthIsUpWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void productionContextDoesNotExposeProbeControllers() throws Exception {
        mockMvc.perform(post("/internal/validation-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sensor\",\"count\":3}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("stackTrace"))));

        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();

        mockMvc.perform(get("/internal/error-probe")
                        .header("Authorization", "Bearer test-ingestion-key-0123456789abcdef"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(content().string(not(containsString("super-secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }
}
