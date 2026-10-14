package com.streamforge.api.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record CanonicalReading(
        UUID eventId,
        String deviceId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        BigDecimal temperatureC,
        BigDecimal humidityPct,
        BigDecimal batteryPct,
        byte[] canonicalHash) {

    public CanonicalReading {
        canonicalHash = canonicalHash.clone();
    }

    @Override
    public byte[] canonicalHash() {
        return canonicalHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CanonicalReading reading)) {
            return false;
        }
        return schemaVersion == reading.schemaVersion
                && eventId.equals(reading.eventId)
                && deviceId.equals(reading.deviceId)
                && eventType.equals(reading.eventType)
                && occurredAt.equals(reading.occurredAt)
                && temperatureC.equals(reading.temperatureC)
                && humidityPct.equals(reading.humidityPct)
                && batteryPct.equals(reading.batteryPct)
                && Arrays.equals(canonicalHash, reading.canonicalHash);
    }

    @Override
    public int hashCode() {
        int result = eventId.hashCode();
        result = 31 * result + deviceId.hashCode();
        result = 31 * result + eventType.hashCode();
        result = 31 * result + schemaVersion;
        result = 31 * result + occurredAt.hashCode();
        result = 31 * result + temperatureC.hashCode();
        result = 31 * result + humidityPct.hashCode();
        result = 31 * result + batteryPct.hashCode();
        result = 31 * result + Arrays.hashCode(canonicalHash);
        return result;
    }
}
