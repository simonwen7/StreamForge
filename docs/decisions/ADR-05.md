# ADR-05: Transactional Outbox and Idempotent At-Least-Once Processing

- **Status:** Accepted
- **Decision date:** M1

## Context

The API must durably accept an event in PostgreSQL and later publish it to Kafka. A direct database/Kafka dual write has failure windows that can lose an event or publish an event whose database transaction failed. Kafka and process failures can also produce duplicate delivery.

## Decision

Use a Transactional Outbox for publication, accept at-least-once delivery, and make business effects idempotent. Implement and validate these mechanisms progressively from M3 through M6. Do not claim end-to-end exactly-once delivery.

## Rationale

Writing acceptance and outbox intent in one PostgreSQL transaction removes the unsafe dual-write gap. Retrying publication prevents loss but permits duplicates. Stable event identity plus atomic consumer deduplication and business updates makes those duplicates safe at the business level.

## Alternatives

- **Write PostgreSQL then Kafka directly:** can commit data without publishing after a crash.
- **Write Kafka then PostgreSQL directly:** can publish data whose acceptance record fails.
- **Distributed transactions:** add substantial coordination and operational complexity and are not a practical baseline here.
- **Rely only on Kafka exactly-once features:** does not by itself atomically cover external PostgreSQL business effects end to end.

## Consequences / Trade-offs

- HTTP acceptance is decoupled from publication and processing.
- Outbox polling/claiming, retries, backlog monitoring, and cleanup must be operated.
- Duplicate publications and deliveries are expected conditions.
- Consumers require durable idempotency records and carefully ordered transaction/offset handling.
- Failure-injection testing is part of correctness, not optional hardening.

## Risks and Deferred Implementation Details

Outbox schema, publisher ownership, claiming, leases, retry limits, dead-letter policy, retention, deduplication scope, transaction isolation, offset acknowledgement, replay behavior, and operational recovery remain for M3–M6. PostgreSQL and Kafka cannot be treated as one atomic transaction merely by framework configuration.

## Related Documents

- [Architecture](../architecture.md)
- [Testing Strategy](../testing-strategy.md)

