# ADR-08: Versioned `telemetry.reading` V1 Contract

- **Status:** Accepted
- **Decision date:** M1

## Context

Reliable retry and asynchronous processing require a stable identity and explicit schema. Clients also need to distinguish durable acceptance from completed processing, while the system must reject accidental reuse of an identity for another payload.

## Decision

Define a versioned `telemetry.reading` V1 event with a stable UUID `eventId`, `deviceId`, `eventType`, `schemaVersion`, UTC `occurredAt`, measurement fields, and server-generated `receivedAt` and `processedAt`. A new durably accepted event returns HTTP `202`; identical retries are idempotent; conflicting reuse of an event ID is rejected. Final wire, validation, response, and storage details require M3 review.

## Rationale

Explicit identity supports safe retries, outbox correlation, Kafka redelivery, and Worker idempotency. Version and type make evolution intentional. Separating event, acceptance, and processing times prevents misleading latency and chronology assumptions.

## Alternatives

- **Server-generated event IDs only:** makes ambiguous client retry after a lost response harder to deduplicate.
- **Unversioned payload:** makes compatibility changes implicit and unsafe.
- **Treat every retry as a new event:** duplicates business effects.
- **Return success only after processing:** couples request availability and latency to Kafka and Worker health.

## Consequences / Trade-offs

- Producers must create and preserve UUID event IDs across retries.
- The API must compare existing and retried material content.
- HTTP `202` means accepted for asynchronous work, not processed.
- Server timestamps are authoritative only for StreamForge lifecycle stages.
- Contract evolution requires explicit compatibility behavior.

## Risks and Deferred Implementation Details

Canonical equality, UUID constraints, field ranges/precision, optionality, timestamp tolerances, endpoint and response schemas, conflict status, lifecycle representation, Kafka envelope, topic/key, schema enforcement, and compatibility windows remain open until M3.

## Amendment — 2026-10-14, owner-approved for M3

The M1 decision above remains the historical record. During M3 design review the owner approved a nested measurements object and rejected the flat measurement fields shown in the original M1 example.

The implemented V1 request therefore places `temperatureC`, `humidityPct`, and `batteryPct` inside `measurements`. Flat payloads are invalid. `processedAt` is not part of the M3 acceptance response. A new durable acceptance returns HTTP `202`; an identical retry returns HTTP `200` and preserves `receivedAt`; conflicting reuse of an event ID returns HTTP `409`.

The change was made so the wire contract matches the approved M3 implementation rather than leaving two undocumented payload shapes. Canonical equality, storage, and the acceptance response are specified in the event contract.

## Related Documents

- [Event Contract](../event-contract.md)
- [Requirements](../requirements.md)

