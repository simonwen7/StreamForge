# ADR-07: IoT Telemetry and Device Monitoring as the MVP Domain

- **Status:** Accepted
- **Decision date:** M1

## Context

An event platform can support many domains, but a credible MVP needs one coherent vocabulary, contract, user journey, read model, and failure story. Multiple domains would obscure whether core event-processing behavior works.

## Decision

Use IoT Telemetry & Device Monitoring as the sole initial MVP domain.

## Rationale

Telemetry naturally demonstrates event time, late and duplicate events, asynchronous processing, device-oriented read models, monitoring, and sustained ingestion. A narrow domain keeps requirements and acceptance criteria testable while preserving useful architecture exercises.

## Alternatives

- **Multiple sample domains:** broadens demonstration value but fragments contracts and delays depth.
- **Generic arbitrary event ingestion:** appears reusable but shifts validation and product semantics to consumers.
- **Another single domain:** possible, but not the approved product direction.

## Consequences / Trade-offs

- Initial contracts, APIs, data models, UI, and tests use telemetry language.
- General platform abstractions are added only when concrete needs justify them.
- Domain assumptions such as device identity, measurement bounds, and stale-state rules must still be resolved.
- Supporting another domain later may expose coupling that requires refactoring.

## Risks and Deferred Implementation Details

Device enrollment, tenancy, location, units beyond the V1 fields, device status, alerting, aggregates, late-data policy, retention, and realistic workload distribution are open. M1 does not approve additional event domains.

## Related Documents

- [Requirements](../requirements.md)
- [Event Contract](../event-contract.md)

