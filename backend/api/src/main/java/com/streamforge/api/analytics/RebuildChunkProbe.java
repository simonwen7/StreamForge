package com.streamforge.api.analytics;

import java.time.Instant;

/**
 * Test hook invoked inside the open day transaction, before it commits.
 * Production does not register an implementation.
 */
@FunctionalInterface
interface RebuildChunkProbe {

    void beforeCommit(Instant dayStart);
}
