package com.streamforge.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourcePasswordGuardTests {

    @Test
    void blankPasswordFailsWithoutEchoingTheValue() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setPassword(" ");

        assertThatThrownBy(() -> new DataSourcePasswordGuard(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DataSourcePasswordGuard.MISSING_PASSWORD);
    }

    @Test
    void unresolvedPlaceholderFailsWithoutEchoingTheValue() {
        assertThatThrownBy(() -> DataSourcePasswordGuard.requireNonBlank("${POSTGRES_PASSWORD}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DataSourcePasswordGuard.MISSING_PASSWORD);
    }

    @Test
    void missingPasswordFailsWithoutEchoingTheValue() {
        assertThatThrownBy(() -> new DataSourcePasswordGuard(new DataSourceProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(DataSourcePasswordGuard.MISSING_PASSWORD);
    }

    @Test
    void nonBlankPasswordIsAcceptedAndNotIncludedInAFailure() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setPassword("local-only-not-logged");

        assertThatCode(() -> new DataSourcePasswordGuard(properties)).doesNotThrowAnyException();
    }
}
