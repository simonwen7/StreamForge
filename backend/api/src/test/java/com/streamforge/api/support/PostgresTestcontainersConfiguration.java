package com.streamforge.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    /**
     * Official PostgreSQL 17.11-bookworm multi-arch index digest verified 2026-10-14.
     */
    public static final String POSTGRES_IMAGE =
            "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        DockerImageName imageName = DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer(imageName);
    }
}
