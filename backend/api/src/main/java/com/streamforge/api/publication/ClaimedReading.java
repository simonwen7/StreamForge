package com.streamforge.api.publication;

import java.util.UUID;

public record ClaimedReading(PendingReading reading, UUID claimToken, int attemptCount) {
}
