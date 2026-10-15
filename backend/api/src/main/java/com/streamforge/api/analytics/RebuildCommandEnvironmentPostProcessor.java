package com.streamforge.api.analytics;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * A rebuild invocation is a short command, not a second API. It must not bind an HTTP port.
 */
public class RebuildCommandEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (Boolean.parseBoolean(environment.getProperty("streamforge.analytics.rebuild.enabled", "false"))) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
