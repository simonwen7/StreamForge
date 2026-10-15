# StreamForge

StreamForge is a planned cloud-native event processing and analytics platform. Its first MVP domain is IoT telemetry and device monitoring: accepting versioned readings, processing them asynchronously, and exposing useful read models while making reliability behavior observable.

The project is intended to demonstrate practical event-driven engineering: explicit contracts, transactional boundaries, at-least-once delivery, idempotent business effects, operational failure handling, and a measured path to AWS. M3 accepts nested telemetry into PostgreSQL. M4 publishes pending outbox rows to a local Kafka broker and records one Worker processing receipt. M7 adds a local operations console and a disposable Compose demo. AWS deployment remains unimplemented.

## Planned Architecture

StreamForge will use a modular repository with two independent Java processes:

- **Backend API:** validates and durably accepts telemetry, serves read APIs, and authorizes protected operations.
- **Worker:** consumes Kafka events, applies idempotent business effects, and updates PostgreSQL-backed read state.
- **PostgreSQL:** primary transactional store, including the planned outbox and processed-event records.
- **Apache Kafka:** asynchronous event transport; a single-node KRaft setup is planned initially for local development.
- **Frontend:** a local operations console in `frontend/`. It reads the API through a same-origin proxy.
- **Infrastructure:** local and deployment configuration added only in the milestones that need it.

Logical modules do not imply separate deployments. The API and Worker are the only planned application deployment units initially. See [Architecture](docs/architecture.md).

## Implemented now

- Java 21, Maven Wrapper, and Spring Boot 4.1.1 in `backend/api`, `backend/worker`, and `backend/simulator`
- JDBC, Flyway V1–V5, and local PostgreSQL 17.11 through Docker Compose
- Device registration and nested `telemetry.reading` acceptance
- Atomic event and outbox insertion, with idempotent retries
- An outbox publisher in the API, off unless `OUTBOX_PUBLISHER_ENABLED=true`, claiming one row at a time
- Apache Kafka 4.2.1, single-node KRaft, loopback host port `127.0.0.1:9094`
- An independent Worker that writes `streamforge.processed_events` and acknowledges Kafka only after commit
- Two loopback API keys for device administration and ingestion
- A manual Java CLI simulator in `backend/simulator`. Repeating a seed is successful: HTTP 202 is a new acceptance and HTTP 200 is the same event already accepted
- Actuator health, request validation, and RFC 9457 error responses
- JUnit and Testcontainers tests, including PostgreSQL and Kafka
- Device-admin analytics for summary, UTC hour and day series, and keyset reading history
- An explicit, default-off rebuild of derived hourly statistics from accepted telemetry
- A React 19 operations console for devices, accepted-telemetry analytics, and historical readings
- Multi-stage images and `infra/compose.demo.yaml`, a disposable project that does not use the preserved development volumes
- GitHub Actions workflows for the backend, the frontend, and the disposable demo. Remote M7 CI is not verified until the owner pushes and the jobs finish.

## Planned for M8

M8 is Cloud Deployment & Final Project Delivery. It owns approved AWS deployment, public-exposure security, cloud observability, final performance testing, final documentation, and portfolio delivery. Public identity is not part of M7.

Dead-letter replay and a longer reliability soak remain open.

## Status

**M1 completed and accepted on 2026-10-14 with documented deferrals.**

**M2 — Java Backend Foundation was accepted by the owner on 2026-10-14.** Local tests passed. The owner reported that remote GitHub Actions passed. This repository's agent did not independently verify that remote run.

**M3 — Event Sources & Ingestion was accepted by the owner.** The local M3 recheck passed. The owner reported that GitHub Actions passed. This agent did not independently verify that remote run.

**M4 — Asynchronous Event Processing was accepted by the owner.** This agent did not independently verify the remote GitHub Actions run.

**M5 — Data Correctness & Reliability was accepted by the owner.** This agent did not independently verify the remote GitHub Actions run.

**M6 — Storage, Analytics & Historical Processing was accepted by the owner on 2026-10-15.** This agent did not independently re-check the remote GitHub Actions run after the isolation fix.

**M7 — Full-Stack Product & Delivery is implemented locally and is pending Final Check / user acceptance.** Remote GitHub Actions for M7 is not verified. M8 has not started.

## Planned Repository Layout

M2 introduced `backend/api` and `infra`. Later directories are created only when they contain real deliverables:

```text
backend/
  api/                 # Spring Boot API process
  simulator/           # Manual HTTP telemetry CLI
  worker/              # Independent Kafka consumer
  shared-contract/     # deferred until a later milestone
frontend/              # local operations console
infra/                 # local PostgreSQL and Kafka Compose
docs/                  # requirements, architecture, decisions, and records
```

## Documentation

- [Requirements](docs/requirements.md)
- [Architecture](docs/architecture.md)
- [Event Contract](docs/event-contract.md)
- [Development](docs/development.md)
- [Testing Strategy](docs/testing-strategy.md)
- [Known Issues and Risks](docs/known-issues.md)
- [Architecture Decision Records](docs/decisions/ADR-01.md)
- [M1 Milestone Report](docs/milestones/M1.md)
- [M2 Milestone Report](docs/milestones/M2.md)
- [M3 Milestone Report](docs/milestones/M3.md)
- [M4 Milestone Report](docs/milestones/M4.md)
- [M5 Milestone Report](docs/milestones/M5.md)
- [M6 Milestone Report](docs/milestones/M6.md)
- [M7 Milestone Report](docs/milestones/M7.md)
- [ADR-13](docs/decisions/ADR-13.md)

## Local Development

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -B -ntp verify
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres kafka
set -a && source infra/.env && set +a
export OUTBOX_PUBLISHER_ENABLED=false
./mvnw -pl backend/api spring-boot:run
```

Leave the publisher disabled against the preserved development database. That database still has pending M3 events, and enabling the publisher sends them. Start the Worker only after Flyway has applied V3:

```bash
export KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9094
./mvnw -pl backend/worker spring-boot:run
```

## Safety and Delivery Boundaries

Kafka and PostgreSQL must remain private. Public demo access, if provided, is read-only; write, replay, failure-injection, and control operations require server-side authorization. No deployment may occur without explicit approval.

