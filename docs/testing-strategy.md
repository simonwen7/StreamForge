# Testing Strategy

## Current Test Status

M1 contains no application code, build, dependencies, database, broker, frontend, or deployment. Consequently, no unit, integration, Kafka, database, end-to-end, security, or performance tests are executable in M1. Documentation structure and references may be checked, but those checks do not validate runtime behavior.

Everything below is a future testing requirement unless explicitly reported otherwise in a later milestone.

## Testing Principles

- Test observable behavior and transaction outcomes, not framework internals.
- Reproduce failure windows that motivate the transactional outbox and idempotency design.
- Separate fast deterministic tests from infrastructure-dependent suites.
- Do not infer end-to-end exactly-once delivery from passing tests.
- Use stable event IDs and assert both resulting state and absence of duplicate effects.
- Make test data, time, retries, and asynchronous waiting controlled and diagnosable.

## Planned Layers

### Unit Tests

Cover validation rules, canonical duplicate comparison, event/status transitions, retry classification, mapping, domain calculations, authorization decisions, and idempotency decisions. Tests should avoid Spring context startup unless the context itself is under test.

### API and Component Tests

Exercise request parsing, authentication/authorization boundaries, validation failures, unsupported versions, `202` acceptance, identical retries, conflicting event IDs, query behavior, error responses, and health endpoints. These should verify that accepted does not imply processed.

### PostgreSQL Integration Tests

Run against a real compatible PostgreSQL instance, preferably through ephemeral containers in test environments. Verify:

- atomic accepted-event and outbox insertion;
- uniqueness and concurrent requests for one event ID;
- canonical conflict detection;
- transaction rollback behavior;
- outbox claiming, lease recovery, and multiple publishers;
- atomic Worker idempotency records and business effects;
- migrations, indexes, constraints, and query plans where material.

An in-memory database is not a substitute for PostgreSQL-specific behavior.

### Kafka Integration Tests

Run against a real Kafka-compatible test broker configuration. Verify serialization, headers/keys, supported versions, consumer-group behavior, partition ordering assumptions, redelivery, offset handling, retries, poison events, and recovery after restarts. Tests must account for eventual consistency without fixed arbitrary sleeps.

### Failure-Injection and Resilience Tests

Deliberately exercise:

- API loss before and after acceptance commit;
- response loss followed by an identical retry;
- Kafka unavailable while API acceptance continues within policy;
- publisher crash before publish, and after publish but before outbox update;
- duplicate Kafka records;
- Worker crash before database commit, and after commit but before offset acknowledgement;
- PostgreSQL interruption and transaction deadlocks/timeouts;
- malformed, unsupported, and poison events;
- prolonged backlog, recovery, and replay.

The suite should assert no lost committed acceptance, no duplicate business effect, visible backlog/failure state, and bounded/recoverable retries according to the final policy.

### End-to-End Tests

From producer request through API, PostgreSQL outbox, Kafka, Worker, read model, query API, and UI, verify lifecycle transitions and representative user journeys. E2E tests are intentionally fewer than lower-level tests and require a complete environment.

### Security Tests

Verify deny-by-default controls; server-side authorization for write, replay, failure-injection, and administration; intentionally public read routes; input and payload limits; secret absence; dependency and container scanning; private Kafka/PostgreSQL exposure; TLS and security headers where applicable; auditability; and abuse/rate-limit behavior.

Threat modeling, identity design, penetration testing scope, and deployment hardening remain to be defined before public exposure.

### Performance and Capacity Tests

After workload goals are approved, measure acceptance latency, publication delay, processing latency, query latency, throughput, outbox growth, consumer lag, database contention, recovery time, and resource use. Include steady-state, burst, prolonged Kafka outage, replay, and recovery scenarios.

M1 defines no fabricated performance thresholds. Baselines, test hardware, data distribution, event size, concurrency, and pass/fail targets must be recorded with results.

## Test Data and Isolation

- Use synthetic, non-sensitive telemetry.
- Give each test isolated event and device IDs.
- Reset state through controlled test fixtures, not production-style destructive controls.
- Control clocks where time semantics are under test.
- Preserve failure diagnostics while avoiding credentials and sensitive payloads in logs.

## Planned CI Gates

When CI exists, likely gates include formatting, compile, unit tests, focused integration tests, migration validation, static analysis, dependency review, secret scanning, and documentation-link checks. Longer E2E, resilience, and performance suites may run separately. The exact tools and mandatory gates are not approved in M1.

## Evidence and Reporting

Every later milestone report should list the exact commands, environment, passing and failing suites, skipped tests and reasons, and links or paths to retained reports. “Not run,” “not applicable,” and “passed” are distinct states.

## Related Documents

- [Requirements](requirements.md)
- [Architecture](architecture.md)
- [Event Contract](event-contract.md)
- [Known Issues and Risks](known-issues.md)

