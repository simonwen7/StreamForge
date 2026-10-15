package com.streamforge.api.telemetry;

import com.streamforge.api.contract.CanonicalReading;
import com.streamforge.api.device.DeviceRepository;
import com.streamforge.api.error.ConflictException;
import com.streamforge.api.error.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestionService {

    private final DeviceRepository deviceRepository;
    private final TelemetryRepository telemetryRepository;

    public IngestionService(DeviceRepository deviceRepository, TelemetryRepository telemetryRepository) {
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
    }

    @Transactional
    public IngestionResult accept(CanonicalReading reading) {
        if (!deviceRepository.exists(reading.deviceId())) {
            throw new NotFoundException("Device was not found.");
        }
        Optional<TelemetryRepository.StoredEvent> inserted = telemetryRepository.insertIfAbsent(reading);
        if (inserted.isPresent()) {
            telemetryRepository.insertOutbox(inserted.get().eventId());
            return result(HttpStatus.ACCEPTED.value(), inserted.get());
        }
        TelemetryRepository.StoredEvent existing = telemetryRepository.find(reading.eventId())
                .orElseThrow(() -> new IllegalStateException("Accepted event row was not visible after a conflict."));
        if (!sameHash(existing.canonicalHash(), reading.canonicalHash())) {
            throw new ConflictException("Event ID was already accepted with different content.");
        }
        return result(HttpStatus.OK.value(), existing);
    }

    @Transactional(readOnly = true)
    public AcceptanceResponse get(UUID eventId) {
        if (new UUID(0L, 0L).equals(eventId)) {
            throw new com.streamforge.api.error.InvalidRequestException(java.util.List.of(
                    new com.streamforge.api.error.InvalidRequestException.FieldError(
                            "eventId", "eventId must not be the nil UUID")));
        }
        TelemetryRepository.StoredEvent existing = telemetryRepository.find(eventId)
                .orElseThrow(() -> new NotFoundException("Event was not found."));
        return response(existing);
    }

    private static boolean sameHash(byte[] stored, byte[] presented) {
        return stored != null && presented != null && MessageDigest.isEqual(stored, presented);
    }

    private static IngestionResult result(int status, TelemetryRepository.StoredEvent event) {
        return new IngestionResult(status, response(event));
    }

    private static AcceptanceResponse response(TelemetryRepository.StoredEvent event) {
        return new AcceptanceResponse(
                event.eventId(),
                event.deviceId(),
                "ACCEPTED",
                event.receivedAt(),
                event.publishedAt() == null ? "PENDING" : "PUBLISHED",
                event.processedAt() == null ? "NOT_STARTED" : "PROCESSED",
                event.processedAt());
    }
}
