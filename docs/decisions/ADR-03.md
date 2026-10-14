# ADR-03: PostgreSQL as the Primary Database

- **Status:** Accepted
- **Decision date:** M1

## Context

StreamForge requires transactional acceptance, uniqueness enforcement, outbox state, idempotent processing records, device/read models, and reliable queries. Analytical volume and long-term storage characteristics are not yet known.

## Decision

PostgreSQL is the primary operational and initial analytics database. S3/Parquet will be reconsidered in M6 based on actual historical storage and analytical requirements.

## Rationale

PostgreSQL provides transactions, constraints, indexing, mature Java support, and operational familiarity. It can atomically store event acceptance and outbox intent and can support the initial read models without introducing multiple data systems prematurely.

## Alternatives

- **S3/Parquet from the start:** potentially useful for analytics and cost-efficient retention, but unsuitable as the sole transactional acceptance store and premature without workload evidence.
- **A NoSQL database:** may scale particular access patterns but weakens the simple relational transaction model needed initially.
- **Separate operational and analytical stores immediately:** increases replication and consistency complexity before requirements justify it.

## Consequences / Trade-offs

- Transactional records and initial read models share one database technology.
- Schema migrations, indexing, backup/restore, retention, and capacity become explicit engineering concerns.
- Large telemetry retention or analytical scans may eventually require tiering or another store.
- Database availability gates durable acceptance and processing.

## Risks and Deferred Implementation Details

Schema, isolation levels, locking, partitioning, retention, connection pooling, migrations, backup, restore, encryption, and high-availability topology are deferred. M6 reconsideration is an evaluation, not a commitment to adopt S3/Parquet.

## Related Documents

- [Architecture](../architecture.md)
- [Known Issues and Risks](../known-issues.md)

