package com.streamforge.api.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record ReadingRequest(
        @NotNull(message = "eventId is required") UUID eventId,
        @NotBlank(message = "deviceId is required")
        @Pattern(regexp = com.streamforge.api.device.DeviceIds.PATTERN, message = "deviceId format is invalid") String deviceId,
        @NotNull(message = "eventType is required") String eventType,
        @NotNull(message = "schemaVersion is required") Integer schemaVersion,
        @NotNull(message = "occurredAt is required") String occurredAt,
        @NotNull(message = "measurements is required") @Valid Measurements measurements) {

    public record Measurements(
            @NotNull(message = "temperatureC is required") BigDecimal temperatureC,
            @NotNull(message = "humidityPct is required") BigDecimal humidityPct,
            @NotNull(message = "batteryPct is required") BigDecimal batteryPct) {
    }
}
