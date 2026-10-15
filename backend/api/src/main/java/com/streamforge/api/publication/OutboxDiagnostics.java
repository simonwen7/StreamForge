package com.streamforge.api.publication;

public record OutboxDiagnostics(
        int pending,
        int eligible,
        int activeClaims,
        int expiredClaims,
        int failed,
        long oldestPendingAgeSeconds) {
}
