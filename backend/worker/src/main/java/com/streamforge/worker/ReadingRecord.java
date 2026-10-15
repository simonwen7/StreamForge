package com.streamforge.worker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record ReadingRecord(
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
