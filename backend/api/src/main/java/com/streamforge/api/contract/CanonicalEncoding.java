package com.streamforge.api.contract;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Version 1 canonical material encoding.
 * Layout: version byte, 16-byte UUID, then length-prefixed UTF-8 fields and fixed-width integers.
 * Lengths are unsigned 16-bit big-endian, so field boundaries do not depend on delimiter characters.
 */
public final class CanonicalEncoding {

    public static final int VERSION = 1;

    private CanonicalEncoding() {
    }

    public static byte[] sha256(CanonicalReading reading) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode(reading));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.");
        }
    }

    public static byte[] encode(CanonicalReading reading) {
        byte[] deviceId = utf8(reading.deviceId());
        byte[] eventType = utf8(reading.eventType());
        byte[] temperature = utf8(canonicalDecimal(reading.temperatureC()));
        byte[] humidity = utf8(canonicalDecimal(reading.humidityPct()));
        byte[] battery = utf8(canonicalDecimal(reading.batteryPct()));
        ByteBuffer buffer = ByteBuffer.allocate(
                1 + 16
                        + 2 + deviceId.length
                        + 2 + eventType.length
                        + 4
                        + 8
                        + 2 + temperature.length
                        + 2 + humidity.length
                        + 2 + battery.length);
        buffer.put((byte) VERSION);
        buffer.putLong(reading.eventId().getMostSignificantBits());
        buffer.putLong(reading.eventId().getLeastSignificantBits());
        writePrefixed(buffer, deviceId);
        writePrefixed(buffer, eventType);
        buffer.putInt(reading.schemaVersion());
        buffer.putLong(epochMicros(reading.occurredAt()));
        writePrefixed(buffer, temperature);
        writePrefixed(buffer, humidity);
        writePrefixed(buffer, battery);
        return buffer.array();
    }

    /**
     * Trailing zeros within an already accepted scale are insignificant.
     * {@code 87.0} and {@code 87.000} both encode as {@code 87}. A negative scale is an exact integer.
     */
    public static String canonicalDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    public static long epochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }

    private static void writePrefixed(ByteBuffer buffer, byte[] value) {
        if (value.length > 65535) {
            throw new IllegalArgumentException("Canonical field is too long.");
        }
        buffer.putShort((short) value.length);
        buffer.put(value);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static UUID uuid(long mostSignificant, long leastSignificant) {
        return new UUID(mostSignificant, leastSignificant);
    }
}
