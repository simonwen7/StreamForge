# ADR-14 — Public simulation and a separate live build

## Status

Accepted for M8 implementation. Final milestone acceptance is still pending.

## Decision

The public interview site is a static Vite build whose only entry is `frontend/src/entries/showcase.tsx`. The Docker console is a second build, `npm run build:live`, whose HTML entry is rewritten to `frontend/src/entries/live.tsx` before Rollup resolves modules. Live routes stay `/devices`, `/devices/:deviceId`, and `/devices/:deviceId/readings`.

The simulation is a pure reducer with a fixed UTC epoch of `2026-06-15T12:00:00Z` plus an elapsed clock. Averages of integer thousandths use half-away-from-zero rounding. The public site does not call the API.

AWS deployment is out of scope for this milestone.

## Consequences

`frontend/Dockerfile` runs `npm run build:live`. Vercel runs `npm run build`. A visitor refresh resets the simulation. Navigation inside the showcase keeps it.
