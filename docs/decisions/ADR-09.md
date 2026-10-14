# ADR-09: Read-Only Public Demo and Protected Control Operations

- **Status:** Accepted
- **Decision date:** M1

## Context

A public demo can make StreamForge easy to evaluate, but telemetry writes and operational actions can consume resources, alter state, expose internals, or disrupt availability. Hiding UI controls does not secure backend operations.

## Decision

Public read-only demo access may be provided. Write, replay, failure-injection, and control operations require server-side authorization. Kafka and PostgreSQL must remain private.

## Rationale

This permits low-friction demonstration while limiting high-impact capabilities. Server-side authorization creates an enforceable boundary independent of the frontend. Keeping data infrastructure private reduces attack surface and prevents clients from bypassing application rules.

## Alternatives

- **Fully public API:** easier to try but exposes mutation, abuse, and operational risk.
- **Require authentication for all reads:** safer by default but reduces demo accessibility.
- **Expose Kafka or PostgreSQL for demonstration:** bypasses business controls and is unacceptable.
- **Client-side-only control hiding:** is not an authorization mechanism.

## Consequences / Trade-offs

- Routes need explicit public/protected classification and deny-by-default behavior.
- Public data must be synthetic or reviewed for disclosure risk.
- Authentication, authorization, audit, abuse controls, and rate limiting add implementation and operational work.
- Infrastructure networking must prevent public broker/database access.

## Risks and Deferred Implementation Details

Identity provider, tenant model, roles, public dataset, session/token design, CORS, CSRF applicability, rate limits, audit retention, TLS termination, network rules, and incident response are deferred. No public access is authorized by this ADR alone.

## Related Documents

- [Requirements](../requirements.md)
- [Known Issues and Risks](../known-issues.md)

