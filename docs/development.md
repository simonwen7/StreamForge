# Development Guide

## Current Phase

M1 contains repository conventions and documentation only. Do not install dependencies, generate application projects, start infrastructure, or deploy as part of M1.

## Environment Audit

The following findings were supplied for the current development machine:

| Item | Observed state | M1 interpretation |
| --- | --- | --- |
| Platform | macOS ARM64 | Target native ARM64 tooling. |
| Java | 17.0.2 x86_64 | Present but not the approved version or native architecture. |
| Java 21 ARM64 | Not installed | Install before M2 after user approval. |
| Homebrew | 6.0.21 | Available; no M1 installation is authorized. |
| Docker CLI/Desktop | Not found at checked locations | Install and verify before infrastructure work. |
| Git | 2.39.5 | Available; Git ownership remains manual. |
| Node.js | 25.6.1 | Current non-LTS line is not an assumed frontend baseline. |
| npm | 11.9.0 | Available, but no packages are installed in M1. |
| Python | 3.14.3 | Available, but Python services are deferred until M6 unless justified. |
| Global Maven | Unavailable | Expected; the project will use Maven Wrapper. |

These are environment observations, not proof that any future build works.

## Prerequisites Before M2

With explicit user approval:

1. Install a Java 21 ARM64 JDK and verify `java` and `javac` resolve to it.
2. Install Docker Desktop or another approved Docker runtime that supports Docker Compose, then verify architecture and resource settings.
3. Generate and commit Maven Wrapper files with the first Java build rather than requiring global Maven.
4. Before frontend implementation, evaluate Node.js 24 LTS and pin the selected runtime in repository configuration.

No installation is performed or authorized by this document.

## Planned Workflow

1. Start from an accepted milestone scope and its relevant ADRs.
2. Create only directories and modules that contain real deliverables.
3. Make a focused change with tests and documentation appropriate to its risk.
4. Run repository-owned formatting, unit, and relevant integration checks.
5. Record unresolved behavior in [Known Issues and Risks](known-issues.md).
6. Request independent review and user acceptance before declaring the milestone complete.

Exact commands will be added when build tooling exists. Documentation-only checks are described in the [M1 report](milestones/M1.md).

## Repository Conventions

- Java production and test code will use the standard Maven directory layout inside each real module.
- API and Worker are separate runnable Spring Boot applications.
- `backend/shared-contract` will remain narrowly scoped to stable versioned contract definitions; it must not become a shared business-logic dumping ground.
- Package names, Maven coordinates, module aggregation, formatting plugins, and test tooling will be selected when the M2 build is created.
- Configuration defaults must be safe for local use and must not contain secrets.
- Environment-specific values belong in environment variables or approved secret/configuration mechanisms.
- Generated output, local data, IDE state, credentials, and logs are ignored.
- Markdown links should be relative so documentation works in repository browsers.

## Planned Structure

```text
backend/
  api/
  worker/
  shared-contract/
frontend/
infra/
docs/
  decisions/
  milestones/
```

Only `docs` and root foundation files exist in M1. Empty source directories are intentionally omitted.

## Branches, Commits, and Repository Ownership

Git actions are owned by the user. Agents and automated implementation steps must not initialize the repository, stage files, commit, push, rewrite history, or configure remotes unless a later request explicitly changes that policy. The user decides branching, commit structure, authorship, remote hosting, and publication.

Before committing manually, the user should inspect the file list and diff, verify that no secrets or generated files are present, and run the checks appropriate to the milestone.

## Configuration and Secrets

- Never commit `.env` files, private keys, tokens, passwords, or cloud credentials.
- An `.env.example` may later document non-secret variable names and safe placeholders.
- Kafka and PostgreSQL endpoints must default to non-public exposure.
- Production secrets must use a reviewed secret-management path before deployment.

## Related Documents

- [Architecture](architecture.md)
- [Testing Strategy](testing-strategy.md)
- [Known Issues and Risks](known-issues.md)

