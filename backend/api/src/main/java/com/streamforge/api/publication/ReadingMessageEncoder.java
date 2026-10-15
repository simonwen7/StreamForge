package com.streamforge.api.publication;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;

final class ReadingMessageEncoder {

    static final String EVENT_ID_HEADER = "event-id";

    private ReadingMessageEncoder() {
    }

    static ProducerRecord<String, String> record(String topic, PendingReading reading) {
        requireEncodable(reading);
        String eventId = reading.eventId().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, reading.deviceId(), json(reading));
        record.headers().add(new RecordHeader(EVENT_ID_HEADER, eventId.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private static void requireEncodable(PendingReading reading) {
        requirePlain(reading.deviceId());
        requirePlain(reading.eventType());
        requirePlain(reading.eventId().toString());
    }

    private static void requirePlain(String value) {
        if (value == null || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('\n') >= 0) {
            throw new PublicationPermanentException("CONTRACT_ENCODING");
        }
    }

    static String json(PendingReading reading) {
        return "{"
                + "\"eventId\":\"" + reading.eventId() + "\","
                + "\"deviceId\":\"" + reading.deviceId() + "\","
                + "\"eventType\":\"" + reading.eventType() + "\","
                + "\"schemaVersion\":" + reading.schemaVersion() + ","
                + "\"occurredAt\":\"" + reading.occurredAt().truncatedTo(ChronoUnit.MICROS) + "\","
                + "\"measurements\":{"
                + "\"temperatureC\":" + reading.temperatureC().toPlainString() + ","
                + "\"humidityPct\":" + reading.humidityPct().toPlainString() + ","
                + "\"batteryPct\":" + reading.batteryPct().toPlainString()
                + "},"
                + "\"receivedAt\":\"" + reading.receivedAt().truncatedTo(ChronoUnit.MICROS) + "\""
                + "}";
    }
}
