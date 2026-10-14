# Known Issues, Risks, and Open Decisions

This register is intentionally explicit. An item is not resolved merely because the target architecture describes a preferred direction.

| ID | Area | Severity | Accountable Role | Risk or uncertainty | Current position / required action | Target milestone / prerequisite gate |
| --- | --- | --- | --- | --- | --- | --- |
| R-01 | Contract | High | Backend Lead | Canonical equality for identical retries is undefined. Raw JSON comparison is unsafe. | Define material fields, normalization, hashing, and conflict response. | M3 — before ingestion implementation |
| R-02 | Contract | High | Backend Lead | Numeric ranges, precision, required/optional fields, device ID rules, and timestamp tolerance are open. | Finalize and test the V1 validation schema. | M3 — before contract and API implementation |
| R-03 | Contract | High | Architecture Owner | Schema evolution and unsupported-version handling lack an enforcement mechanism. | Define compatibility policy and schema distribution/validation. | M3–M4 — before Kafka contract rollout |
| R-04 | Ordering | Medium | Event Processing Lead | Topic count, partition count, event key, and per-device ordering requirements are unapproved. | Validate device-key proposal against ordering and hot-key risks. | M3–M4 — before topic and consumer configuration |
| R-05 | Outbox | High | Backend Lead | Claiming, retry/backoff, attempt limits, lease recovery, retention, and terminal failure handling are undefined. | Design schema and failure state machine; test all crash windows. | M3–M4 — before outbox processing is enabled |
| R-06 | Idempotency | High | Worker Lead | Worker deduplication retention and atomicity details are undefined. | Couple processing record and effects in a PostgreSQL transaction; define cleanup policy. | M4–M5 — before Worker business effects are enabled |
| R-07 | Replay | High | Operations Lead | Replay can bypass normal idempotency or overload the system if poorly designed. | Specify authorized, audited, rate-limited semantics before implementation. | M5–M6 — before replay is enabled |
| R-08 | Kafka | Medium | Platform Lead | Initial single-node KRaft has no broker high availability and is not representative of resilient production. | Limit to local use initially; document recovery and reevaluate deployment topology. | M4 and M9 — before any non-local deployment |
| R-09 | Availability | High | Reliability Lead | Prolonged Kafka outage can grow the outbox and consume PostgreSQL capacity. | Define quotas, backlog alarms, admission policy, and recovery procedure. | M4–M6 — before sustained or public ingestion |
| R-10 | Database | High | Database Lead | Isolation level, locking, migration, backup, restore, retention, and capacity strategy are unapproved. | Design and test before production deployment. | M3 and M9 — before persistence implementation and deployment approval |
| R-11 | Read models | Medium | Product Owner | Device status, stale thresholds, analytics, and late/out-of-order update behavior are undefined. | Agree product semantics before implementation. | M3–M6 — before analytics acceptance |
| R-12 | SLOs | Medium | Reliability Lead | No availability, latency, throughput, retention, RTO, or RPO targets exist. | Establish measurable workload and reliability targets. | M6–M8 — before performance acceptance |
| R-13 | Security | Critical | Security Lead | Identity provider, tenant model, roles, credential flow, rate limiting, audit policy, and threat model are open. | Complete security design before exposing any write/control route or public deployment. | M3–M9 — before write/control exposure or public deployment |
| R-14 | Public demo | High | Product Owner | Read-only data could still expose sensitive device information or enable abuse. | Use synthetic/sanitized data, least privilege, payload limits, and reviewed public routes. | M8 — before public exposure |
| R-15 | Network | Critical | Platform Lead | Kafka and PostgreSQL could be exposed by incorrect Compose/cloud configuration. | M2 Compose publishes PostgreSQL as `127.0.0.1:5432` only; local inspect confirmed `HostIp=127.0.0.1`. Re-verify after any Compose change and before Kafka or cloud exposure. | M2, M4, and M9 — before database configuration, broker exposure, and deployment approval |
| R-16 | Secrets | High | Security Lead | No production secret-management mechanism is selected. | Keep secrets out of Git and choose an approved mechanism before deployment. | M9 — before cloud deployment |
| R-17 | AWS | High | Cloud Architecture Lead | EC2 plus Docker Compose may have security, resilience, backup, patching, and operational limitations. | Compare alternatives and document cost/resource/security trade-offs; require explicit approval. | M9 — before deployment approval |
| R-18 | AWS cost | Medium | Cloud Architecture Lead | Instance, storage, transfer, logging, backup, and public endpoint costs are unestimated. | Produce a bounded estimate and shutdown/budget controls before deployment. | M9 — before creating paid resources |
| R-19 | Resources | High | Performance Lead | Co-locating API, Worker, Kafka, PostgreSQL, and UI may exhaust a small EC2 host. | Measure CPU, memory, disk, and recovery behavior before selecting topology. | M8–M9 — before deployment topology selection |
| R-20 | Storage | Medium | Data Architecture Lead | PostgreSQL growth and analytical-query impact may become limiting; S3/Parquet is deferred. | Measure workload and reconsider S3/Parquet without presuming migration. | M6 — before any historical-storage architecture change |
| R-21 | Toolchain | High | Development Environment Owner | Current Java is 17 x86_64; Java 21 ARM64 and Docker are absent. Node 25 is not the intended LTS baseline. | Temurin 21 ARM64 and Docker Desktop are installed for M2. Java 17 x86_64 remains present and must not be selected. Evaluate Node 24 LTS before frontend work. | M2 prerequisite — before implementation starts |
| R-22 | Observability | Medium | Reliability Lead | Tooling, metrics, alert thresholds, correlation format, and retention are not selected. | Define alongside runtime implementation and failure tests. | M3–M8 — before operational acceptance |
| R-23 | Operations | High | Operations Lead | Dead-letter, poison-event, incident, data correction, and disaster-recovery procedures are undefined. | Define, authorize, test, and document operational runbooks. | M4–M9 — before replay or public deployment |
| R-24 | Privacy | High | Security Lead | Data classification and retention/deletion requirements are unknown. | Confirm telemetry sensitivity and legal/product requirements before public use. | M8 — before public exposure |
| R-25 | Architecture | Medium | Architecture Owner | Outbox publisher initially belongs to the API deployment, but scaling and operational ownership may require refinement. | Evaluate without creating an additional service prematurely. | M3–M6 — before changing deployment-unit boundaries |
| R-26 | Process | High | Project Supervisor | Milestone status could become inconsistent or work could begin without explicit authorization. | M1 is accepted. Keep M2 pending Final Check; do not start M3 automatically. | M1 acceptance record and M2 authorization gate — before M2 starts |
| R-27 | Supply chain | High | Security Lead | Vulnerable third-party dependencies, compromised Maven dependencies or plugins, untrusted container images, dependency version drift, unverifiable artifact provenance or integrity, and CI dependency or secret exposure could compromise builds or runtime artifacts. | M2 pins Spring Boot 4.1.1 BOM-managed libraries, the Maven Wrapper SHA-256, the official PostgreSQL index digest, the Testcontainers Ryuk 0.14.0 index digest, and GitHub Action commit SHAs. Testcontainers 2.0.5 honors that Ryuk pin and warns that configuration image substitution is deprecated. Maven logs nonfatal HTTP 401 responses while reading prefix metadata for repositories declared by `org.flywaydb:flyway-parent:12.4.0`. No dependency CVE scanner is installed, so vulnerability status is unverified. Continue review before adding dependencies. | M2 and M8 — baseline hygiene before adding dependencies; CI security gates before public deployment |
| R-28 | Toolchain | Medium | Backend Lead | Spring Boot 4.1, JUnit 6, Flyway 12, and Testcontainers 2 APIs are newer than many examples and may change test or configuration behavior. | Use official Boot 4.1.1 coordinates and Maven Central artifacts; keep tests on real PostgreSQL. Re-check APIs before M3. | M2–M3 — before expanding persistence and Kafka integration |

## Known Current Limitations

- M2 provides API foundation only; no telemetry events are accepted, published, processed, or displayed.
- Kafka, Worker, outbox, replay, and frontend remain unimplemented.
- Remote GitHub Actions has not run until the user pushes the workflow.
- Local Compose uses placeholder development credentials, not production secrets.
- Flyway metadata lookup warns on nonfatal HTTP 401 responses from repositories declared by `flyway-parent` 12.4.0. No CVE scan has been run.
- Architecture descriptions still include planned later-milestone behavior.

## Review Practice

When an item is resolved, retain its history or supersede it with a decision record; do not silently delete risk context. New approved architecture decisions require their own review rather than being implied by edits to this register.

## Related Documents

- [Requirements](requirements.md)
- [Architecture](architecture.md)
- [Event Contract](event-contract.md)
- [M1 Milestone](milestones/M1.md)
- [M2 Milestone](milestones/M2.md)

