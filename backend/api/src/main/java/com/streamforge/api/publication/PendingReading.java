package com.streamforge.api.publication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PendingReading(
        UUID eventId,
        String deviceId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        BigDecimal temperatureC,
        BigDecimal humidityPct,
        BigDecimal batteryPct,
        Instant receivedAt) {
}
