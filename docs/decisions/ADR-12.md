# ADR-12: Raw Telemetry Analytics and UTC-Day Rebuilds

- **Status:** Accepted for M6 implementation
- **Decision date:** 2026-10-15

## Context

M5 stores one accepted telemetry row per `event_id` and one Worker receipt per event. Analytics has to survive duplicate Kafka delivery, late `occurred_at` values, and receipts that already exist for historical rows. A receipt does not prove that an aggregate was updated.

## Decision

`streamforge.telemetry_events` is the only source for the analytics API. Every accepted row counts, including pending and failed publication. The API does not wait for a rebuild and does not filter on `processed_events`.

Derived storage is `streamforge.device_hour_stats`, rebuilt from raw rows by an explicit command. The command expands the request to complete UTC days, replaces one day per transaction, and checkpoints `next_chunk_start`. A session-level `pg_try_advisory_lock` covers the invocation and is released on the same connection. `max_source_received_at` records the greatest ingestion time inside the recomputed bucket. It is not a completeness watermark.

Buckets use `occurred_at` in UTC. Intervals are half-open. Averages use `ROUND(AVG(numeric), 3)`. The three read routes require the device-admin key.

S3/Parquet and a Python analytics service are not adopted. PostgreSQL remains the analytics database at the measured M6 sizes.

## Revisions from the first design note

1. Rebuild whole UTC days. Never delete a wider interval than the insert recomputes. Exact midnight `to` does not include the next day.
2. The advisory lock is one dedicated session for the whole run. Days commit independently. Resume is by `run_id`. A stale `running` row stays resumable and does not block a new run once the lock is free.
3. The bucket column is `max_source_received_at`, not a watermark, and there is no `isFinal` flag.
4. Scale-3 values such as `21.705` stay valid. `21.7051` stays invalid.
5. `EXPLAIN (ANALYZE, BUFFERS)` is evidence. A sequential scan is not automatically a defect, and 100 ms is not a pass/fail gate.

## Consequences / Trade-offs

- Late events change past query results and can make a rebuilt hour stale until that UTC day is rebuilt again.
- The derived table can lag. The API does not serve that lag.
- The rebuild command exits the process. It is not a fourth service.
- Dashboard charts remain M7. Large-scale tuning remains M10.

## Related Documents

- [M6](../milestones/M6.md)
- [ADR-03](ADR-03.md)
- [ADR-11](ADR-11.md)
