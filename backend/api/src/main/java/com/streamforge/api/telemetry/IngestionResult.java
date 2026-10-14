package com.streamforge.api.telemetry;

public record IngestionResult(int httpStatus, AcceptanceResponse response) {
}
