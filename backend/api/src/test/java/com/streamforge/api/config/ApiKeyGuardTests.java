package com.streamforge.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyGuardTests {

    @Test
    void rejectsBlankShortPlaceholderAndIdenticalKeys() {
        assertThatThrownBy(() -> ApiKeyGuard.require(null, "a".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.ADMIN_INVALID);
        assertThatThrownBy(() -> ApiKeyGuard.require(" ", "b".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.ADMIN_INVALID);
        assertThatThrownBy(() -> ApiKeyGuard.require("${DEVICE_ADMIN_API_KEY}", "b".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.ADMIN_INVALID);
        assertThatThrownBy(() -> ApiKeyGuard.require("a".repeat(31), "b".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.ADMIN_INVALID);
        assertThatThrownBy(() -> ApiKeyGuard.require("a".repeat(32), "a".repeat(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.KEYS_MUST_DIFFER);
    }

    @Test
    void postProcessorFailureDoesNotEchoTheConfiguredValue() {
        String secret = "local-only-key-value-0123456789abcdef";
        MockEnvironment environment = new MockEnvironment()
                .withProperty(ApiKeyGuard.ADMIN_PROPERTY, secret)
                .withProperty(ApiKeyGuard.INGESTION_PROPERTY, secret);
        assertThatThrownBy(() -> new ApiKeyEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ApiKeyGuard.KEYS_MUST_DIFFER)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(secret));
    }

    @Test
    void acceptsDistinctLongKeys() {
        ApiKeyGuard.require("a".repeat(32), "b".repeat(32));
    }
}
