package com.streamforge.api.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record AcceptanceResponse(
        UUID eventId,
        String deviceId,
        String acceptance,
        Instant receivedAt,
        String publication,
        String processing,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant processedAt) {
}
