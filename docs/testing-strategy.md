# Testing Strategy

## Current Test Status

M2 and M3 execute local Maven tests. The command is:

```bash
./mvnw -B -ntp verify
```

Covered now, and executed locally:

- Spring Boot application context startup against a fresh disposable PostgreSQL database via Testcontainers
- Flyway V1 applied on that fresh database, including checksum `595439424`, followed by V2
- Actuator health without component details
- MVC slice coverage for validation field names, malformed JSON, sanitized unexpected errors, and a database failure that does not claim acceptance
- Device creation, duplicate and invalid IDs, lookup, unknown devices, and bounded listing
- Nested telemetry validation, canonical hashing, identical and conflicting retries, and parallel requests for one event ID
- Event plus outbox commit, and rollback when the outbox insert fails
- API-key 401 and 403 boundaries, and startup rejection of missing or identical keys
- The simulator's bounded, deterministic HTTP behavior without printing its credential

The MVC slice keeps security filters off so it can reach the test probes. The full application context requires a credential for those paths and does not invoke the probes. Unknown business routes still require authentication.

Not executed:

- Kafka publication, Worker processing, replay, end-to-end product journeys, and performance tests

The owner reported that the M2 GitHub Actions run passed. This agent did not independently verify it. Remote GitHub Actions for M3 is **NOT VERIFIED** until the user pushes this milestone.

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

## CI Gates

M2 adds `.github/workflows/backend-ci.yml`, which runs `./mvnw -B -ntp verify` on Java 21 for pushes to `main` and pull requests. That workflow has been written and locally reviewed; a remote GitHub Actions run is **NOT VERIFIED** until the user pushes the code.

Later gates may add formatting, static analysis, dependency review, secret scanning, and documentation-link checks. Longer E2E, resilience, and performance suites may run separately. Those additional tools are not approved in M2.

## Evidence and Reporting

Every later milestone report should list the exact commands, environment, passing and failing suites, skipped tests and reasons, and links or paths to retained reports. “Not run,” “not applicable,” and “passed” are distinct states.

## Related Documents

- [Requirements](requirements.md)
- [Architecture](architecture.md)
- [Event Contract](event-contract.md)
- [Known Issues and Risks](known-issues.md)
- [M2 Milestone](milestones/M2.md)

