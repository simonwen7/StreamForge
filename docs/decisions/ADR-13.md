# ADR-13: Local Dashboard, Rolling Windows, and Disposable Delivery

- **Status:** Accepted for M7 implementation. Pending Final Check / User Acceptance.
- **Decision date:** 2026-10-15

## Context

M1–M6 are complete. The owner consolidated the remaining work into two milestones. M7 delivers the operational dashboard and the local container/CI path. M8 delivers approved cloud deployment, public identity, cloud observability, final performance testing, and portfolio delivery. Consolidation reduces milestone overhead. It does not reduce the technical scope.

The owner approved the M7 design with four binding revisions.

## Decision

### Rolling windows

Relative presets are Last 24 hours, Last 7 days, and Last 30 days. Each refresh generation captures one `Date` and derives `from` and `to` from that instant. Summary and series in that generation use those same UTC bounds. The overview refreshes every 15 seconds while the document is visible, and again when it becomes visible. A custom absolute range does not move on that interval. Query keys include the device and the UTC bounds, and they do not include the admin key. Stale requests are cancelled with `AbortSignal`.

### Simulator clock

The default simulator clock stays `2026-01-01T00:00:00Z` plus the event index. `--clock now` is valid mode only. It captures one UTC anchor, truncates it to seconds, and spreads readings across the previous six hours, ending at that anchor. No generated `occurredAt` is after the anchor. Measurements stay seed-deterministic. Event IDs stay seed-deterministic. Because `occurredAt` is part of the canonical hash, repeating a seed at a later anchor can return HTTP 409. A live demonstration uses a new seed. `--allow-service-host true` permits only `http://api:<port>` so the disposable Compose network can run the simulator. Every other host remains rejected.

### Numbers

The API serializes PostgreSQL `numeric` as JSON numbers. `JSON.parse` produces a JavaScript `Number` and does not preserve lexical trailing zeroes: `21.700` becomes `21.7`. The dashboard uses that number for chart coordinates and formats display text to at most three fractional digits, without turning null into zero. PostgreSQL remains the authority for counts and aggregates. The UI does not recompute them.

### Credentials and delivery

The device-admin key is typed into the browser and kept in React state. It is not written to `localStorage`, `sessionStorage`, the URL, source, Vite environment variables, the production bundle, or an image layer. Sign-out and a page refresh clear it. The cache is cleared on sign-out. A 401 signs the operator out. This is a local demonstration, not a public identity system.

The ingestion key stays in the simulator environment. The disposable Compose project is `streamforge-m7demo`, with its own PostgreSQL and Kafka volumes. Those services publish no host ports. The web UI publishes `127.0.0.1:4173` only. The API publishes `127.0.0.1:18080` only so a simulator on the host or in CI can reach that demo API. Inside the demo, the API listens on `0.0.0.0`. Outside Docker, the API default remains `127.0.0.1`. The publisher is enabled only for that empty demo database. The preserved project stays publisher-disabled. `docker compose down -v` is not used against project `streamforge`.

Playwright traces, video, and screenshots are off. CI generates ephemeral keys, masks them, and deletes only the Compose project it created.

## Consequences

- Public deployment remains blocked on M8. R-13, R-15, R-27, and R-30 stay open.
- A browser user can still read the in-memory key from developer tools during the session.
- A repeated live seed can conflict. The demo procedure uses a new seed each run.
- Recharts is loaded with the overview route. The initial devices route does not include it. Measured bundle sizes are recorded in the M7 report rather than assumed.

## Related Documents

- [M7](../milestones/M7.md)
- [ADR-12](ADR-12.md)
- [Development](../development.md)
