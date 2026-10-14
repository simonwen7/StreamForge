# ADR-06: AWS Goal with EC2 and Docker Compose as a Candidate

- **Status:** Accepted
- **Decision date:** M1

## Context

StreamForge should ultimately demonstrate cloud deployment, but the workload, security model, resource needs, operating cost, backup strategy, and resilience requirements are not yet measured. Choosing a managed or distributed topology now would be speculative.

## Decision

Treat AWS deployment as a final goal. Prefer EC2 plus Docker Compose as the candidate for evaluation, subject to M9 cost, security, operational, and resource review. Perform no deployment without explicit user approval.

## Rationale

An EC2/Compose deployment could preserve parity with local containers and keep a demonstration environment understandable. Deferring commitment allows measurements and security requirements to inform whether that candidate is actually acceptable.

## Alternatives

- **ECS, EKS, or managed application platforms:** may improve orchestration but add platform cost and complexity.
- **Managed Kafka and PostgreSQL:** may improve operations and resilience but can materially increase cost.
- **No cloud target:** would not meet the project's intended cloud engineering value.

## Consequences / Trade-offs

- Infrastructure is not an M1 deliverable.
- A single-host candidate may have correlated failure, maintenance, and capacity limitations.
- M9 must include cost estimates, resource measurements, exposure review, backup/restore, patching, monitoring, and shutdown controls.
- Explicit approval remains a deployment gate even if M9 recommends the candidate.

## Risks and Deferred Implementation Details

AWS account structure, region, IAM, VPC, DNS, TLS, images, registries, instance type, storage, backups, availability, logging, budgets, and teardown are unapproved. “Preferred candidate” does not mean selected production architecture.

## Related Documents

- [Known Issues and Risks](../known-issues.md)
- [Architecture](../architecture.md)

