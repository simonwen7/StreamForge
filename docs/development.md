# Development Guide

## Current Phase

M2 is implemented and tested locally. It remains pending Final Check and user acceptance. Remote GitHub Actions has not run. Do not start M3 automatically.

## Environment Audit

| Item | M1 observation | Current M2 observation |
| --- | --- | --- |
| Platform | macOS ARM64 | Unchanged. |
| Java | 17.0.2 x86_64 only | Eclipse Temurin 21.0.12.1 ARM64 is installed and is the StreamForge default. Java 17 x86_64 remains present and must not be used for this project. |
| Docker | Not found | Docker Desktop 4.95.0 / Engine 29.8.2 linux/arm64 and Compose v5.6.0 are installed. |
| Homebrew | 6.0.21 | Available. |
| Git | 2.39.5 | Available; Git ownership remains manual. |
| Node.js | 25.6.1 | Still not an assumed frontend baseline. Evaluate Node.js 24 LTS before frontend work. |
| Global Maven | Unavailable | Expected; the repository uses Maven Wrapper 3.3.4 / Maven 3.10.0. |

Select Java 21 explicitly:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

## Local Commands

From the repository root:

```bash
./mvnw --version
./mvnw -B -ntp verify
```

Start local PostgreSQL (loopback only):

```bash
cp infra/.env.example infra/.env   # if you do not already have a local file
docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres
docker compose --env-file infra/.env -f infra/compose.yaml ps
docker inspect --format '{{json .HostConfig.PortBindings}}' streamforge-postgres-1
```

Run the API against that database:

```bash
set -a && source infra/.env && set +a
./mvnw -pl backend/api spring-boot:run
```

Health check:

```bash
curl -i http://127.0.0.1:8080/actuator/health
```

Stop the local database after use:

```bash
docker compose --env-file infra/.env -f infra/compose.yaml down
```

Do not commit `infra/.env`.

## Repository Conventions

- Java 21, Maven Wrapper, and Spring Boot 4.1.1 are the M2 baseline.
- The only application module is `backend/api`. Worker and shared-contract directories are still omitted until they contain real deliverables.
- Package root is `com.streamforge.api`.
- Production code has no telemetry, replay, or administrative business endpoints.
- Test-only controllers live in `src/test/java` under `com.streamforge.testing`, outside the production component scan, and are not packaged in the API jar.
- PostgreSQL host publishing must remain `127.0.0.1:${POSTGRES_PORT:-5432}:5432`.
- `POSTGRES_PASSWORD` is required and must be non-empty. The API fails startup when it is missing or blank, and the failure text does not include the password.
- The API listens on `127.0.0.1` unless `SERVER_ADDRESS` is set. Local `.env` files keep `127.0.0.1`. A container or cloud process that must accept traffic from outside the host sets `SERVER_ADDRESS=0.0.0.0`.
- Configuration uses environment variables. `.env` files are ignored except `.env.example`.

## Flyway V1

`V1__create_platform_schema.sql` has already been applied to the local Compose volume. Its stored checksum is `595439424`. Do not edit that file. A checksum change would make Flyway validation fail on existing databases, and repairing history is not approved.

Flyway creates schema `streamforge` from `spring.flyway.schemas` before the script runs. The script also contains `CREATE SCHEMA IF NOT EXISTS streamforge`, so PostgreSQL emits `42P06` and Flyway logs a warning. The statement is conditional, the migration still succeeds, and removing it would change the checksum. Leave V1 unchanged.

`streamforge.schema_info` is a one-row marker, not a telemetry schema. Flyway history already records that V1 succeeded. The safest later removal is a new versioned migration, reviewed when a real schema is designed. Do not drop the table, repair history, or recreate the volume in M2.

## Network binding

| Environment | API bind | How it is selected |
| --- | --- | --- |
| Local development | `127.0.0.1` | Default `SERVER_ADDRESS`, also set in `infra/.env.example` |
| Container or cloud host | `0.0.0.0` | Set `SERVER_ADDRESS=0.0.0.0` in that environment only |
| Tests | Mock MVC, no listen socket | `@SpringBootTest` uses the mock web environment |

PostgreSQL remains published on `127.0.0.1` only. Changing the API bind address does not change the database publish address.

## Planned Structure

```text
backend/
  api/                 # Spring Boot API process (M2)
  worker/              # deferred until needed
  shared-contract/     # deferred until needed
frontend/              # deferred
infra/                 # local PostgreSQL Compose
docs/
  decisions/
  milestones/
```

## Branches, Commits, and Repository Ownership

Git actions are owned by the user. Agents must not initialize the repository, stage files, commit, push, rewrite history, or configure remotes unless a later request explicitly changes that policy.

## Configuration and Secrets

- Never commit `.env` files, private keys, tokens, passwords, or cloud credentials.
- `infra/.env.example` documents local variable names and safe placeholders.
- Kafka and PostgreSQL endpoints must default to non-public exposure.
- Production secrets must use a reviewed secret-management path before deployment.

## Related Documents

- [Architecture](architecture.md)
- [Testing Strategy](testing-strategy.md)
- [Known Issues and Risks](known-issues.md)
- [M2 Milestone](milestones/M2.md)
