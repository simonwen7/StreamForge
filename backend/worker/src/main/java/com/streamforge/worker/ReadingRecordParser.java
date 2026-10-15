package com.streamforge.worker;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class ReadingRecordParser {

    static final String EVENT_ID_HEADER = "event-id";
    private static final String EVENT_TYPE = "telemetry.reading";
    private static final int SCHEMA_VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    ReadingRecord parse(ConsumerRecord<String, String> record) {
        Payload payload = readPayload(record.value());
        if (payload.eventId() == null
                || payload.deviceId() == null
                || payload.deviceId().isBlank()
                || payload.eventType() == null
                || payload.schemaVersion() == null
                || payload.occurredAt() == null
                || payload.receivedAt() == null
                || payload.measurements() == null
                || payload.measurements().temperatureC() == null
                || payload.measurements().humidityPct() == null
                || payload.measurements().batteryPct() == null) {
            throw new PoisonMessageException("Telemetry record is missing a required field.");
        }
        if (record.key() == null || !record.key().equals(payload.deviceId())) {
            throw new PoisonMessageException("Kafka key does not match deviceId.");
        }
        Header header = record.headers().lastHeader(EVENT_ID_HEADER);
        if (header == null) {
            throw new PoisonMessageException("event-id header is missing.");
        }
        String headerEventId = new String(header.value(), StandardCharsets.UTF_8);
        if (!headerEventId.equals(payload.eventId().toString())) {
            throw new PoisonMessageException("event-id header does not match eventId.");
        }
        if (!EVENT_TYPE.equals(payload.eventType())) {
            throw new PoisonMessageException("eventType is not supported.");
        }
        if (payload.schemaVersion() != SCHEMA_VERSION) {
            throw new PoisonMessageException("schemaVersion is not supported.");
        }
        return new ReadingRecord(
                payload.eventId(),
                payload.deviceId(),
                payload.eventType(),
                payload.schemaVersion(),
                parseInstant(payload.occurredAt(), "occurredAt"),
                payload.measurements().temperatureC(),
                payload.measurements().humidityPct(),
                payload.measurements().batteryPct(),
                parseInstant(payload.receivedAt(), "receivedAt"));
    }

    private Payload readPayload(String json) {
        if (json == null || json.isBlank()) {
            throw new PoisonMessageException("Telemetry record is not valid JSON.");
        }
        try {
            return mapper.readValue(json, Payload.class);
        } catch (RuntimeException ex) {
            if (duplicateKey(ex)) {
                throw new PoisonMessageException("Telemetry record contains a duplicate JSON key.");
            }
            throw new PoisonMessageException("Telemetry record is not valid JSON.");
        }
    }

    private static boolean duplicateKey(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof StreamReadException && isDuplicateMessage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isDuplicateMessage(String message) {
        return message != null
                && (message.startsWith("Duplicate field") || message.startsWith("Duplicate Object property"));
    }

    private static java.time.Instant parseInstant(String value, String field) {
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw new PoisonMessageException(field + " is not a valid timestamp.");
        }
    }

    private record Payload(
            UUID eventId,
            String deviceId,
            String eventType,
            Integer schemaVersion,
            String occurredAt,
            Measurements measurements,
            String receivedAt) {
    }

    private record Measurements(BigDecimal temperatureC, BigDecimal humidityPct, BigDecimal batteryPct) {
    }
}
