package com.streamforge.api.analytics;

import com.streamforge.api.error.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Opaque keyset position for descending {@code (occurred_at, event_id)} order.
 * The token is not a SQL fragment.
 */
final class ReadingCursor {

    private ReadingCursor() {
    }

    static String encode(Instant occurredAt, UUID eventId) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, occurredAt);
        String raw = micros + "." + eventId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int dot = raw.indexOf('.');
            if (dot < 1 || dot != raw.lastIndexOf('.')) {
                throw invalid();
            }
            long micros = Long.parseLong(raw.substring(0, dot));
            UUID eventId = UUID.fromString(raw.substring(dot + 1));
            if (new UUID(0L, 0L).equals(eventId)) {
                throw invalid();
            }
            return new Position(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), eventId);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException(List.of(
                new InvalidRequestException.FieldError("cursor", "cursor is invalid")));
    }

    record Position(Instant occurredAt, UUID eventId) {
    }
}
