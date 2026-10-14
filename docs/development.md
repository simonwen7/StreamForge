# Development Guide

## Current Phase

M2 was accepted by the owner on 2026-10-14. The owner reported that remote GitHub Actions passed; this agent did not independently verify that run. M3 is implemented locally and is pending Final Check. Do not start M4 automatically.

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
- Application modules are `backend/api` and `backend/simulator`. The simulator is a manual CLI, not a second server. Worker and shared-contract directories are still omitted.
- Package roots are `com.streamforge.api` and `com.streamforge.simulator`.
- M3 device and telemetry routes require bearer API keys. Replay and Kafka publication are not implemented.
- Test-only controllers live in `src/test/java` under `com.streamforge.testing`, outside the production component scan, and are not packaged in the API jar.
- PostgreSQL host publishing must remain `127.0.0.1:${POSTGRES_PORT:-5432}:5432`.
- `POSTGRES_PASSWORD`, `DEVICE_ADMIN_API_KEY`, and `INGESTION_API_KEY` are required. The API fails startup when any required secret is missing, blank, or, for the API keys, shorter than 32 characters or identical. Failure text names the variable and does not include the value.
- The API listens on `127.0.0.1` unless `SERVER_ADDRESS` is set. Local `.env` files keep `127.0.0.1`. A container or cloud process that must accept traffic from outside the host sets `SERVER_ADDRESS=0.0.0.0`.
- Configuration uses environment variables. `.env` files are ignored except `.env.example`.

## Flyway V1

`V1__create_platform_schema.sql` has already been applied to the local Compose volume. Its stored checksum is `595439424`. Do not edit that file. A checksum change would make Flyway validation fail on existing databases, and repairing history is not approved.

Flyway creates schema `streamforge` from `spring.flyway.schemas` before the script runs. The script also contains `CREATE SCHEMA IF NOT EXISTS streamforge`, so PostgreSQL emits `42P06` and Flyway logs a warning. The statement is conditional, the migration still succeeds, and removing it would change the checksum. Leave V1 unchanged.

`streamforge.schema_info` is a one-row marker. V2 does not drop it. Do not repair history or recreate the volume.

## Flyway V2

`V2__create_ingestion_tables.sql` creates `devices`, `telemetry_events`, and `outbox_events`. It is additive. The outbox status check allows `pending`, `published`, and `failed` so a later publisher can record publication. M3 inserts only `pending` rows with `published_at` null.

## API keys and the simulator

Generate two different local keys and put them only in `infra/.env`:

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Device routes use `DEVICE_ADMIN_API_KEY`. Telemetry routes use `INGESTION_API_KEY`. Health does not require a key. Create a device before running the simulator. The simulator refuses a non-loopback base URL, sends at most 100 events, and reads the ingestion key from the environment.

```bash
java -jar backend/simulator/target/streamforge-simulator-0.1.0-SNAPSHOT.jar \
  --base-url http://127.0.0.1:8080 \
  --device-id sensor-042 \
  --seed 7 \
  --count 2 \
  --mode valid
```

`--mode valid` sends distinct events for the seed. HTTP 202 means newly accepted and HTTP 200 means that identical event was already accepted. Both are success. HTTP 409, authentication failures, and server errors fail the run. Running the same seed again against unchanged data is safe and exits 0.

`--mode duplicate` repeats one event ID and payload. A new event returns 202 and then 200. An event that was already accepted returns 200 on every request, including further repeats. A 202 after the event has already been accepted, or a 409, fails the run.

`--mode conflict` sends a baseline event, then the same ID with different content. The baseline may return 202 or 200. Each conflicting submission must return 409. A 200 or 202 on that submission fails the run. If stored data for the seed is not this baseline, the first response is 409 and the run fails instead of treating the mismatch as success.

`--interval-ms` waits between sends and must be from 0 through 60000. The simulator checks that 202 and 200 bodies are ingestion acceptances for the submitted event. It does not treat an arbitrary 2xx response as acceptance.

CSRF is disabled because callers authenticate with a bearer header and the API does not create a cookie session. CORS is disabled, so browsers are not granted cross-origin access. Loopback binding is not a substitute for the API keys.

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
  api/                 # Spring Boot API process
  simulator/           # Manual HTTP CLI (M3)
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
