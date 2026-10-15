package com.streamforge.api.analytics;

import java.time.Instant;
import java.util.UUID;

public record RebuildResult(
        Outcome outcome,
        UUID runId,
        String status,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant nextChunkStart,
        int chunksCommitted,
        String errorCode) {

    public enum Outcome {
        COMPLETED,
        ALREADY_COMPLETED,
        FAILED,
        LOCK_NOT_ACQUIRED,
        REJECTED
    }

    static RebuildResult rejected(UUID runId, String errorCode) {
        return new RebuildResult(Outcome.REJECTED, runId, null, null, null, null, 0, errorCode);
    }

    static RebuildResult lockNotAcquired() {
        return new RebuildResult(Outcome.LOCK_NOT_ACQUIRED, null, null, null, null, null, 0, "LOCK_HELD");
    }

    static RebuildResult completed(RebuildRepository.RebuildRun run) {
        return from(Outcome.COMPLETED, run, null);
    }

    static RebuildResult alreadyCompleted(RebuildRepository.RebuildRun run) {
        return from(Outcome.ALREADY_COMPLETED, run, null);
    }

    static RebuildResult failed(RebuildRepository.RebuildRun run, String errorCode) {
        return from(Outcome.FAILED, run, errorCode);
    }

    private static RebuildResult from(Outcome outcome, RebuildRepository.RebuildRun run, String errorCode) {
        return new RebuildResult(
                outcome,
                run.runId(),
                run.status(),
                run.effectiveFrom(),
                run.effectiveTo(),
                run.nextChunkStart(),
                run.chunksCommitted(),
                errorCode == null ? run.errorCode() : errorCode);
    }
}
