package com.streamforge.api.contract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalEncodingTests {

    private static final UUID EVENT_ID = UUID.fromString("2b4f9f6b-69b8-48b5-9c8d-974f89012ed1");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T23:45:12.483000Z");

    @Test
    void versionByteAndLengthPrefixAvoidDelimiterCollisions() {
        byte[] encoded = CanonicalEncoding.encode(reading("ab", "c", new BigDecimal("21.7")));
        assertThat(encoded[0]).isEqualTo((byte) 1);

        byte[] swapped = CanonicalEncoding.sha256(reading("a", "bc", new BigDecimal("21.7")));
        assertThat(swapped).isNotEqualTo(CanonicalEncoding.sha256(reading("ab", "c", new BigDecimal("21.7"))));
    }

    @Test
    void trailingZerosAndEquivalentOffsetsHashIdentically() {
        byte[] first = CanonicalEncoding.sha256(reading("sensor-042", "telemetry.reading", new BigDecimal("87.0")));
        byte[] second = CanonicalEncoding.sha256(reading("sensor-042", "telemetry.reading", new BigDecimal("87.000")));
        byte[] third = CanonicalEncoding.sha256(reading("sensor-042", "telemetry.reading", new BigDecimal("21.70")));

        assertThat(first).hasSize(32);
        assertThat(Arrays.equals(first, second)).isTrue();
        assertThat(CanonicalEncoding.canonicalDecimal(new BigDecimal("87.0"))).isEqualTo("87");
        assertThat(CanonicalEncoding.canonicalDecimal(new BigDecimal("87.000"))).isEqualTo("87");
        assertThat(CanonicalEncoding.canonicalDecimal(new BigDecimal("21.70"))).isEqualTo("21.7");
        assertThat(Arrays.equals(first, third)).isFalse();

        CanonicalReading offset = new CanonicalReading(
                EVENT_ID,
                "sensor-042",
                "telemetry.reading",
                1,
                Instant.parse("2026-10-14T19:45:12.483-04:00"),
                new BigDecimal("21.7"),
                new BigDecimal("48.2"),
                new BigDecimal("87.0"),
                new byte[32]);
        assertThat(CanonicalEncoding.sha256(offset)).isEqualTo(CanonicalEncoding.sha256(
                reading("sensor-042", "telemetry.reading", new BigDecimal("87.0"))));
    }

    @Test
    void materialChangesChangeTheHash() {
        byte[] original = CanonicalEncoding.sha256(reading("sensor-042", "telemetry.reading", new BigDecimal("87")));
        CanonicalReading otherEvent = new CanonicalReading(
                UUID.fromString("2b4f9f6b-69b8-48b5-9c8d-974f89012ed2"),
                "sensor-042",
                "telemetry.reading",
                1,
                OCCURRED_AT,
                new BigDecimal("21.7"),
                new BigDecimal("48.2"),
                new BigDecimal("87"),
                new byte[32]);
        assertThat(CanonicalEncoding.sha256(otherEvent)).isNotEqualTo(original);
    }

    private static CanonicalReading reading(String deviceId, String eventType, BigDecimal battery) {
        return new CanonicalReading(
                EVENT_ID,
                deviceId,
                eventType,
                1,
                OCCURRED_AT,
                new BigDecimal("21.7"),
                new BigDecimal("48.2"),
                battery,
                new byte[32]);
    }
}
