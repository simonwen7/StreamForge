package com.streamforge.api.device;

import java.util.List;

public record DevicePage(List<DeviceResponse> devices, int limit, int offset) {
}
