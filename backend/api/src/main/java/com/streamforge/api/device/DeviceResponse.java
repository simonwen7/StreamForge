package com.streamforge.api.device;

import java.time.Instant;

public record DeviceResponse(String deviceId, Instant createdAt) {
}
