package com.streamforge.api.telemetry;

import java.time.Instant;
import java.util.UUID;

public record AcceptanceResponse(
        UUID eventId,
        String deviceId,
        String acceptance,
        Instant receivedAt,
        String publication,
        String processing) {
}
