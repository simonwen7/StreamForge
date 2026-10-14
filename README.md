# StreamForge

StreamForge is a planned cloud-native event processing and analytics platform. Its first MVP domain is IoT telemetry and device monitoring: accepting versioned readings, processing them asynchronously, and exposing useful read models while making reliability behavior observable.

The project is intended to demonstrate practical event-driven engineering: explicit contracts, transactional boundaries, at-least-once delivery, idempotent business effects, operational failure handling, and a measured path to AWS. No business functionality, runtime service, database, broker, frontend, or deployment is implemented in M1.

## Planned Architecture

StreamForge will use a modular repository with two independent Java processes:

- **Backend API:** validates and durably accepts telemetry, serves read APIs, and authorizes protected operations.
- **Worker:** consumes Kafka events, applies idempotent business effects, and updates PostgreSQL-backed read state.
- **PostgreSQL:** primary transactional store, including the planned outbox and processed-event records.
- **Apache Kafka:** asynchronous event transport; a single-node KRaft setup is planned initially for local development.
- **Frontend:** a later read-oriented demo and operational UI.
- **Infrastructure:** local and deployment configuration added only in the milestones that need it.

Logical modules do not imply separate deployments. The API and Worker are the only planned application deployment units initially. See [Architecture](docs/architecture.md).

## Planned Technology Stack

- Java 21 and Spring Boot
- Maven Wrapper
- Apache Kafka
- PostgreSQL
- A frontend stack to be selected before frontend implementation; Node.js 24 LTS will be evaluated
- Docker Compose for local infrastructure and, subject to M9 review and explicit approval, a possible EC2 deployment

## Status

**M1 completed and accepted on 2026-10-14 with documented deferrals.**

M2 — Java Backend Foundation is the next milestone and has not started. This repository currently contains documentation and repository-level conventions only; no application runtime functionality has been implemented.

## Planned Repository Layout

Directories will be created when their milestone introduces real content:

```text
backend/
  api/                 # Spring Boot API process
  worker/              # independent Spring Boot worker process
  shared-contract/     # narrowly scoped shared event contract models
frontend/              # demo and operational UI
infra/                 # local and approved deployment infrastructure
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

## Safety and Delivery Boundaries

Kafka and PostgreSQL must remain private. Public demo access, if provided, is read-only; write, replay, failure-injection, and control operations require server-side authorization. No deployment may occur without explicit approval.

