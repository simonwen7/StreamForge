package com.streamforge.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

import java.time.Clock;

@Configuration
public class PlatformConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The telemetry contract rejects scalar coercion, fractional integers, and unknown JSON fields.
     * Write-side defaults are unchanged.
     */
    @Bean
    JsonMapperBuilderCustomizer strictIngestionJson() {
        return builder -> builder
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
