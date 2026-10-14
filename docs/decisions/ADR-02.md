# ADR-02: Apache Kafka for Asynchronous Event Processing

- **Status:** Accepted
- **Decision date:** M1

## Context

Accepted telemetry must be processed asynchronously and decoupled from request latency. The platform also needs observable consumer lag, replay-oriented capabilities, partitioned ordering, and failure exercises representative of event-driven systems.

## Decision

Use Apache Kafka for asynchronous event transport. The initial local environment, planned for M4, will use single-node KRaft through Docker Compose.

## Rationale

Kafka provides durable ordered partitions, consumer groups, retention, and an ecosystem appropriate for demonstrating asynchronous processing and recovery. KRaft avoids a separate ZooKeeper dependency. A single local node limits setup cost while application behavior is being developed.

## Alternatives

- **Synchronous API-to-Worker calls:** couple availability and do not satisfy the event-processing objective.
- **Database queue only:** reduces infrastructure but provides a different operational and consumption model.
- **RabbitMQ or a cloud-managed queue:** credible choices, but Kafka better matches the approved stream-processing and replay direction.
- **Managed Kafka immediately:** adds cloud cost and deployment work before the architecture is validated.

## Consequences / Trade-offs

- Kafka becomes an operational dependency for publication and processing, but not for the API's atomic acceptance transaction.
- Applications must handle redelivery, serialization compatibility, consumer lag, and partition behavior.
- Single-node local Kafka provides no broker high availability.
- Docker is required before the planned local broker can run.

## Risks and Deferred Implementation Details

Topic names, partition count, replication, key choice, retention, compaction, producer acknowledgements, retries, dead-letter handling, schema enforcement, security, and production topology remain open. Single-node KRaft must not be represented as production-resilient.

## Related Documents

- [Architecture](../architecture.md)
- [Testing Strategy](../testing-strategy.md)
- [Known Issues and Risks](../known-issues.md)

