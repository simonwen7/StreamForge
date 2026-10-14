package com.streamforge.api.config;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rejects a missing or blank database password before a connection is opened.
 * The exception text intentionally omits the supplied value.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourcePasswordGuard {

    static final String MISSING_PASSWORD = "POSTGRES_PASSWORD must be set to a non-empty value.";

    public DataSourcePasswordGuard(DataSourceProperties properties) {
        requireNonBlank(properties.getPassword());
    }

    static void requireNonBlank(String password) {
        if (password == null || password.isBlank() || "${POSTGRES_PASSWORD}".equals(password)) {
            throw new IllegalStateException(MISSING_PASSWORD);
        }
    }
}
