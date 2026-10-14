package com.streamforge.api.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs after configuration files are loaded and before a database connection is opened.
 */
public class DataSourcePasswordEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String password;
        try {
            password = environment.getProperty("spring.datasource.password");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(DataSourcePasswordGuard.MISSING_PASSWORD);
        }
        DataSourcePasswordGuard.requireNonBlank(password);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
