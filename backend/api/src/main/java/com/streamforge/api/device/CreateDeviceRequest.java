package com.streamforge.api.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateDeviceRequest(
        @NotBlank(message = "deviceId is required")
        @Pattern(regexp = DeviceIds.PATTERN, message = "deviceId format is invalid") String deviceId) {
}
