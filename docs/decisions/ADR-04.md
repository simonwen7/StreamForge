# ADR-04: Modular Repository with Separate API and Worker Processes

- **Status:** Accepted
- **Decision date:** M1

## Context

The system has distinct synchronous API and asynchronous processing workloads, yet the initial team and product scope do not justify a fleet of independently governed microservices. Repository and runtime boundaries must stay understandable.

## Decision

Use one repository with modular boundaries. Run the Backend API and Java Worker as separate processes. Do not split logical components into additional services without demonstrated need.

## Rationale

A single repository simplifies coordinated contract changes, local development, review, and build management. Separate API and Worker processes preserve independent lifecycle and scaling characteristics. This captures the needed runtime separation without premature network, deployment, and ownership overhead.

## Alternatives

- **Single process:** simplest packaging, but couples request serving to consumer lifecycle, scaling, and failures.
- **Multiple repositories and microservices:** maximizes independence but adds release, network, observability, and contract coordination cost.
- **Serverless functions:** could isolate handlers but changes transaction, runtime, and local-development assumptions.

## Consequences / Trade-offs

- Modules need enforced ownership despite repository proximity.
- API and Worker can deploy and scale independently.
- A small shared contract module is permitted, but shared business logic must not erase boundaries.
- Coordinated changes are easier, while the repository build may grow over time.

## Risks and Deferred Implementation Details

Exact Maven aggregation, package boundaries, module dependency rules, CI paths, ownership, and deployment artifacts are deferred. The outbox publisher is initially a logical API responsibility; moving it to another deployment unit would require evidence and review, not an implicit microservice split.

## Related Documents

- [Architecture](../architecture.md)
- [Development Guide](../development.md)

