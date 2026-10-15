package com.streamforge.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

    private final ProcessingRepository repository;

    public ProcessingService(ProcessingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(ReadingRecord reading) {
        ProcessingRepository.StoredReading stored = repository.findTelemetry(reading.eventId())
                .orElseThrow(() -> new PoisonMessageException("No persisted telemetry event matches eventId."));
        if (!sameReading(stored, reading)) {
            throw new PoisonMessageException("Persisted event does not match the Kafka record.");
        }
        int inserted = repository.insertReceipt(reading.eventId());
        if (inserted == 1) {
            log.info("Recorded processing receipt eventId={}", reading.eventId());
        } else {
            log.info("Duplicate delivery already had a processing receipt eventId={}", reading.eventId());
        }
    }

    private static boolean sameReading(ProcessingRepository.StoredReading stored, ReadingRecord reading) {
        return stored.deviceId().equals(reading.deviceId())
                && stored.eventType().equals(reading.eventType())
                && stored.schemaVersion() == reading.schemaVersion()
                && stored.occurredAt().equals(reading.occurredAt())
                && stored.receivedAt().equals(reading.receivedAt())
                && sameNumber(stored.temperatureC(), reading.temperatureC())
                && sameNumber(stored.humidityPct(), reading.humidityPct())
                && sameNumber(stored.batteryPct(), reading.batteryPct());
    }

    private static boolean sameNumber(BigDecimal stored, BigDecimal presented) {
        return stored.compareTo(presented) == 0;
    }
}
