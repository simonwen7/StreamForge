# StreamForge

StreamForge is a planned cloud-native event processing and analytics platform. Its first MVP domain is IoT telemetry and device monitoring: accepting versioned readings, processing them asynchronously, and exposing useful read models while making reliability behavior observable.

The project is intended to demonstrate practical event-driven engineering: explicit contracts, transactional boundaries, at-least-once delivery, idempotent business effects, operational failure handling, and a measured path to AWS. M2 adds a Java API foundation, local PostgreSQL, and tests. Telemetry ingestion, Kafka processing, the Worker, and AWS deployment remain unimplemented.

## Planned Architecture

StreamForge will use a modular repository with two independent Java processes:

- **Backend API:** validates and durably accepts telemetry, serves read APIs, and authorizes protected operations.
- **Worker:** consumes Kafka events, applies idempotent business effects, and updates PostgreSQL-backed read state.
- **PostgreSQL:** primary transactional store, including the planned outbox and processed-event records.
- **Apache Kafka:** asynchronous event transport; a single-node KRaft setup is planned initially for local development.
- **Frontend:** a later read-oriented demo and operational UI.
- **Infrastructure:** local and deployment configuration added only in the milestones that need it.

Logical modules do not imply separate deployments. The API and Worker are the only planned application deployment units initially. See [Architecture](docs/architecture.md).

## Implemented now

- Java 21, Maven Wrapper, and Spring Boot 4.1.1 in `backend/api`
- JDBC, Flyway, and local PostgreSQL 17.11 through Docker Compose
- Actuator health, request validation, and RFC 9457 error responses
- JUnit and Testcontainers tests, exercised locally
- A GitHub Actions workflow that is written and not yet executed remotely

## Planned for later milestones

- Apache Kafka, the transactional outbox, and the Worker
- Telemetry ingestion, device APIs, and replay
- A frontend stack, with Node.js 24 LTS to be evaluated before that work
- AWS deployment, only after M9 review and explicit approval

## Status

**M1 completed and accepted on 2026-10-14 with documented deferrals.**

**M2 — Java Backend Foundation is implemented and tested locally. It is pending Final Check / user acceptance. Remote GitHub Actions is not verified.**

The API module, Maven Wrapper, Flyway foundation schema, loopback-only PostgreSQL Compose file, and Testcontainers tests exist. No telemetry business endpoints, Kafka, Worker, or cloud deployment are implemented. M3 must not start automatically.

## Planned Repository Layout

M2 introduced `backend/api` and `infra`. Later directories are created only when they contain real deliverables:

```text
backend/
  api/                 # Spring Boot API process (M2)
  worker/              # deferred until a later milestone
  shared-contract/     # deferred until a later milestone
frontend/              # demo and operational UI
infra/                 # local PostgreSQL Compose
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

## Local Development

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./mvnw -B -ntp verify
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres
set -a && source infra/.env && set +a
./mvnw -pl backend/api spring-boot:run
```

## Safety and Delivery Boundaries

Kafka and PostgreSQL must remain private. Public demo access, if provided, is read-only; write, replay, failure-injection, and control operations require server-side authorization. No deployment may occur without explicit approval.

