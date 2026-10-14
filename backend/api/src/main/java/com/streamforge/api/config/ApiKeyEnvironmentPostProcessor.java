package com.streamforge.api.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Rejects missing API keys after configuration is loaded and before the server accepts traffic.
 */
public class ApiKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String adminKey = read(environment, ApiKeyGuard.ADMIN_PROPERTY, ApiKeyGuard.ADMIN_INVALID);
        String ingestionKey = read(environment, ApiKeyGuard.INGESTION_PROPERTY, ApiKeyGuard.INGESTION_INVALID);
        try {
            ApiKeyGuard.require(adminKey, ingestionKey);
        } catch (IllegalStateException ex) {
            throw ex;
        }
    }

    private static String read(ConfigurableEnvironment environment, String property, String message) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(message);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
