package com.streamforge.api.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class RebuildCommandEnvironmentPostProcessorTests {

    @Test
    void enabledRebuildDoesNotStartAWebServer() {
        SpringApplication application = new SpringApplication();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("streamforge.analytics.rebuild.enabled", "true");
        new RebuildCommandEnvironmentPostProcessor().postProcessEnvironment(environment, application);
        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
    }

    @Test
    void normalStartupKeepsTheWebServer() {
        SpringApplication application = new SpringApplication();
        WebApplicationType before = application.getWebApplicationType();
        new RebuildCommandEnvironmentPostProcessor()
                .postProcessEnvironment(new MockEnvironment(), application);
        assertThat(application.getWebApplicationType()).isEqualTo(before);
    }
}
