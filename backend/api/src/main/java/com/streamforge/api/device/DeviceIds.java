package com.streamforge.api.device;

import java.util.regex.Pattern;

public final class DeviceIds {

    public static final String PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private DeviceIds() {
    }

    public static boolean isValid(String deviceId) {
        return deviceId != null && COMPILED.matcher(deviceId).matches();
    }
}
