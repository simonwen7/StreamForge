# ADR-10: M1 Scope Is Documentation and Repository Foundation

- **Status:** Accepted
- **Decision date:** M1

## Context

Core architecture and reliability behavior must be explicit before tools, modules, services, and infrastructure make assumptions expensive to change. The current machine also lacks the approved Java and Docker prerequisites.

## Decision

Limit M1 to requirements, architecture, ADRs, repository foundation, engineering records, and documentation. Do not add application code, dependencies, empty source directories, infrastructure, or deployment.

## Rationale

This creates a reviewable baseline and exposes uncertainty before implementation. It also avoids presenting generated scaffolding or unverified environments as progress and preserves user control over installations and Git history.

## Alternatives

- **Generate services during M1:** provides runnable structure sooner but mixes architecture acceptance with implementation choices.
- **Install all tooling immediately:** may be useful later but is outside the approved scope and requires user approval.
- **Leave decisions informal:** reduces writing but increases contradiction and hidden assumptions.

## Consequences / Trade-offs

- M1 produces no runtime functionality and no executable application tests.
- Planned paths are documented but empty source directories are absent.
- M2 begins only after independent Final Check and user acceptance.
- Later implementation must update records when approved details are resolved.

## Risks and Deferred Implementation Details

Documentation can still contain errors or become stale, so cross-document review and milestone acceptance remain necessary. Build generation, package naming, dependency selection, installations, Git initialization, CI, and runtime verification are deferred.

## Related Documents

- [M1 Milestone Report](../milestones/M1.md)
- [Development Guide](../development.md)
- [README](../../README.md)

