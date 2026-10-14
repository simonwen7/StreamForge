package com.streamforge.api.contract;

import com.streamforge.api.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadingValidatorTests {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private final ReadingValidator validator = new ReadingValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsBoundaryValuesAndNormalizesTrailingZeros() {
        CanonicalReading reading = validator.validate(request(
                UUID.randomUUID(),
                "sensor-042",
                "telemetry.reading",
                1,
                "2026-10-14T08:00:00-04:00",
                new BigDecimal("-40.000"),
                new BigDecimal("0.0"),
                new BigDecimal("100.000")));

        assertThat(reading.occurredAt()).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
        assertThat(reading.temperatureC()).isEqualByComparingTo("-40");
        assertThat(reading.batteryPct()).isEqualByComparingTo("100");
        assertThat(reading.canonicalHash()).hasSize(32);
    }

    @Test
    void acceptsExactlyFiveMinutesAheadAndRejectsAnythingLater() {
        validator.validate(request(
                UUID.randomUUID(),
                "sensor-042",
                "telemetry.reading",
                1,
                "2026-10-14T12:05:00Z",
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0")));

        assertThatThrownBy(() -> validator.validate(request(
                UUID.randomUUID(),
                "sensor-042",
                "telemetry.reading",
                1,
                "2026-10-14T12:05:00.000001Z",
                new BigDecimal("0"),
                new BigDecimal("0"),
                new BigDecimal("0"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsNilUuidExcessiveScaleAndTooManyFractionalDigits() {
        assertThatThrownBy(() -> validator.validate(request(
                new UUID(0L, 0L),
                "sensor-042",
                "telemetry.reading",
                1,
                "2026-10-14T12:00:00.1234567Z",
                new BigDecimal("21.7001"),
                new BigDecimal("48.2"),
                new BigDecimal("87"))))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(ex -> {
                    InvalidRequestException invalid = (InvalidRequestException) ex;
                    assertThat(invalid.errors()).extracting(InvalidRequestException.FieldError::field)
                            .contains("eventId", "occurredAt", "measurements.temperatureC");
                });
    }

    @Test
    void rejectsUnsupportedTypeVersionAndMissingOffset() {
        assertThatThrownBy(() -> validator.validate(request(
                UUID.randomUUID(),
                "sensor-042",
                "other.event",
                2,
                "2026-10-14T12:00:00",
                new BigDecimal("21.7"),
                new BigDecimal("-0.1"),
                new BigDecimal("100.1"))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void equivalentNumericAndOffsetFormsProduceTheSameHash() {
        UUID eventId = UUID.fromString("2b4f9f6b-69b8-48b5-9c8d-974f89012ed1");
        CanonicalReading zulu = validator.validate(request(
                eventId, "sensor-042", "telemetry.reading", 1,
                "2026-10-14T12:00:00.483Z",
                new BigDecimal("21.70"), new BigDecimal("48.200"), new BigDecimal("87.0")));
        CanonicalReading offset = validator.validate(request(
                eventId, "sensor-042", "telemetry.reading", 1,
                "2026-10-14T08:00:00.483-04:00",
                new BigDecimal("21.7"), new BigDecimal("48.2"), new BigDecimal("87.000")));
        assertThat(offset.canonicalHash()).isEqualTo(zulu.canonicalHash());
    }

    private static ReadingRequest request(
            UUID eventId,
            String deviceId,
            String eventType,
            int schemaVersion,
            String occurredAt,
            BigDecimal temperature,
            BigDecimal humidity,
            BigDecimal battery) {
        return new ReadingRequest(
                eventId,
                deviceId,
                eventType,
                schemaVersion,
                occurredAt,
                new ReadingRequest.Measurements(temperature, humidity, battery));
    }
}
