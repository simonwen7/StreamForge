# ADR-11: Single-Row Outbox Claims and Unbounded Transient Retries

- **Status:** Accepted
- **Decision date:** M5

## Context

M4 publishes outbox rows from one API process without a lease. Two enabled publishers can send the same row. A crash after the broker acknowledgement sends it again. A failing head-of-line row blocks the rest of the poll. Transient Kafka outages must not turn a valid accepted event into a terminal failure.

## Decision

Claim one eligible outbox row at a time with `SELECT ... FOR UPDATE SKIP LOCKED`, commit that statement, and send to Kafka only after a fenced update that increments `attempt_count` and extends the lease. Every publish, release, renewal, and permanent-failure update includes the current claim token. A polling cycle may handle at most 20 rows, each with its own 30-second lease. The lease is longer than the 10-second send wait.

`attempt_count` counts publication attempts that passed the pre-send fence. Claiming a row does not increment it. A crash after the increment and before the socket send can still count as an attempt.

Transient broker, network, and database failures stay `pending`, with exponential backoff from 1 second to 5 minutes and about ±20 percent jitter. Passing 20 attempts logs a warning and does not change the row to `failed`. `failed` is reserved for a demonstrably non-retryable encoding or record defect. The telemetry row and outbox row remain. An operator requeues a `failed` row with SQL. There is no requeue HTTP API.

Worker poison classification uses SQLState for constraint failures and leaves connection failures on the main topic. Dead-letter publication stays confirmed before the source offset is acknowledged. Dead-letter records omit the stack-trace header. Duplicate JSON object keys are rejected.

At-least-once delivery remains. An expired lease can overlap a send that is still in flight. The processing receipt primary key absorbs that duplicate.

## Consequences / Trade-offs

- Two API processes can publish different rows without sharing an active claim.
- A stale token cannot mark a newer claim published or clear it.
- A long Kafka outage retries automatically. The outbox can grow.
- `GET` can return `publication=FAILED` for a permanent publication defect. HTTP 202 is unchanged.
- Single-broker replication factor 1 can still lose Kafka bytes after a row is marked published.

## Related Documents

- [ADR-05](ADR-05.md)
- [M5 Milestone](../milestones/M5.md)
