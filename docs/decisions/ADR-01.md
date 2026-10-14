# ADR-01: Java 21 and Spring Boot for API and Worker

- **Status:** Accepted
- **Decision date:** M1

## Context

StreamForge needs a maintainable API and an independently running asynchronous Worker. The stack should support HTTP, transactions, PostgreSQL, Kafka, observability, testing, and long-term operational ownership without introducing multiple server languages at the outset.

## Decision

Use Java 21 with Spring Boot for both the Backend API and independent Worker. Defer Python service development until M6 unless a specific need is justified and approved.

## Rationale

One mature runtime reduces early build, deployment, diagnostics, and skills fragmentation. Java 21 is an LTS release, and Spring Boot has established integrations for the required web, persistence, Kafka, security, and operational concerns. The same stack can produce separate processes without coupling their runtime lifecycle.

## Alternatives

- **Python for one or both services:** productive for analytics, but creates a second runtime and contract/tooling surface before it is needed.
- **Node.js/TypeScript backend:** viable, but does not align with the approved JVM direction.
- **Earlier Java version:** Java 17 is available locally, but Java 21 is the approved baseline.

## Consequences / Trade-offs

- API and Worker share a language and ecosystem but remain separately runnable.
- Java 21 must be installed on the current machine.
- Spring conventions can accelerate delivery but must not hide transaction or Kafka semantics.
- JVM memory and startup costs must be measured for the eventual deployment target.
- Python-based analytical components may still be introduced after M6 review.

## Risks and Deferred Implementation Details

JDK distribution, Spring Boot version, Maven module layout, dependency versions, native versus JVM packaging, memory tuning, and coding conventions are deferred to implementation milestones. The current Java 17 x86_64 installation is not the approved Java 21 ARM64 toolchain.

## Related Documents

- [Development Guide](../development.md)
- [Architecture](../architecture.md)

