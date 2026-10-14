package com.streamforge.api.contract;

import com.streamforge.api.device.DeviceIds;
import com.streamforge.api.error.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ReadingValidator {

    public static final String EVENT_TYPE = "telemetry.reading";
    public static final int SCHEMA_VERSION = 1;
    public static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
    public static final int MAX_SCALE = 3;
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})$");

    private final Clock clock;

    public ReadingValidator(Clock clock) {
        this.clock = clock;
    }

    public CanonicalReading validate(ReadingRequest request) {
        List<InvalidRequestException.FieldError> errors = new ArrayList<>();
        UUID eventId = request.eventId();
        if (eventId == null) {
            errors.add(error("eventId", "eventId is required"));
        } else if (NIL_UUID.equals(eventId)) {
            errors.add(error("eventId", "eventId must not be the nil UUID"));
        }

        if (request.deviceId() == null || request.deviceId().isBlank()) {
            errors.add(error("deviceId", "deviceId is required"));
        } else if (!DeviceIds.isValid(request.deviceId())) {
            errors.add(error("deviceId", "deviceId format is invalid"));
        }

        if (request.eventType() == null) {
            errors.add(error("eventType", "eventType is required"));
        } else if (!EVENT_TYPE.equals(request.eventType())) {
            errors.add(error("eventType", "eventType must be telemetry.reading"));
        }

        if (request.schemaVersion() == null) {
            errors.add(error("schemaVersion", "schemaVersion is required"));
        } else if (request.schemaVersion() != SCHEMA_VERSION) {
            errors.add(error("schemaVersion", "schemaVersion must be 1"));
        }

        Instant occurredAt = parseTimestamp(request.occurredAt(), errors);
        ReadingRequest.Measurements measurements = request.measurements();
        BigDecimal temperature = null;
        BigDecimal humidity = null;
        BigDecimal battery = null;
        if (measurements == null) {
            errors.add(error("measurements", "measurements is required"));
        } else {
            temperature = measurement(measurements.temperatureC(), "measurements.temperatureC", new BigDecimal("-40"), new BigDecimal("125"), errors);
            humidity = measurement(measurements.humidityPct(), "measurements.humidityPct", BigDecimal.ZERO, new BigDecimal("100"), errors);
            battery = measurement(measurements.batteryPct(), "measurements.batteryPct", BigDecimal.ZERO, new BigDecimal("100"), errors);
        }

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }

        CanonicalReading reading = new CanonicalReading(
                eventId,
                request.deviceId(),
                request.eventType(),
                request.schemaVersion(),
                occurredAt,
                temperature,
                humidity,
                battery,
                new byte[32]);
        return new CanonicalReading(
                reading.eventId(),
                reading.deviceId(),
                reading.eventType(),
                reading.schemaVersion(),
                reading.occurredAt(),
                reading.temperatureC(),
                reading.humidityPct(),
                reading.batteryPct(),
                CanonicalEncoding.sha256(reading));
    }

    private Instant parseTimestamp(String value, List<InvalidRequestException.FieldError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(error("occurredAt", "occurredAt is required"));
            return null;
        }
        if (!TIMESTAMP.matcher(value).matches()) {
            errors.add(error("occurredAt", "occurredAt must be an RFC 3339 timestamp with an explicit offset and at most six fractional digits"));
            return null;
        }
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            errors.add(error("occurredAt", "occurredAt must be an RFC 3339 timestamp with an explicit offset and at most six fractional digits"));
            return null;
        }
        if (instant.isAfter(clock.instant().plus(FUTURE_SKEW))) {
            errors.add(error("occurredAt", "occurredAt must not be more than five minutes in the future"));
            return null;
        }
        return instant;
    }

    private static BigDecimal measurement(
            BigDecimal value,
            String field,
            BigDecimal minimum,
            BigDecimal maximum,
            List<InvalidRequestException.FieldError> errors) {
        if (value == null) {
            errors.add(error(field, field + " is required"));
            return null;
        }
        if (value.scale() > MAX_SCALE) {
            errors.add(error(field, field + " must have at most three decimal places"));
            return null;
        }
        BigDecimal normalized = normalize(value);
        if (normalized.compareTo(minimum) < 0 || normalized.compareTo(maximum) > 0) {
            errors.add(error(field, field + " is outside the permitted range"));
            return null;
        }
        return normalized;
    }

    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            return stripped.setScale(0);
        }
        return stripped;
    }

    private static InvalidRequestException.FieldError error(String field, String message) {
        return new InvalidRequestException.FieldError(field, message);
    }
}
