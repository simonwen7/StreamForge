# Development Guide

## Current Phase

M1 through M6 were accepted by the owner. M7 is implemented locally and is pending Final Check. Do not start M8 automatically. Remote M7 CI is not verified until the owner pushes.

## Environment Audit

| Item | M1 observation | Current M2 observation |
| --- | --- | --- |
| Platform | macOS ARM64 | Unchanged. |
| Java | 17.0.2 x86_64 only | Eclipse Temurin 21.0.12.1 ARM64 is installed and is the StreamForge default. Java 17 x86_64 remains present and must not be used for this project. |
| Docker | Not found | Docker Desktop 4.95.0 / Engine 29.8.2 linux/arm64 and Compose v5.6.0 are installed. |
| Homebrew | 6.0.21 | Available. |
| Git | 2.39.5 | Available; Git ownership remains manual. |
| Node.js | 25.6.1 on the default PATH | Frontend commands use Node.js 24. This machine keeps 24.22.0 at `$HOME/.local/node-v24.22.0-darwin-arm64` because Homebrew could not install `node@24`. `frontend/.nvmrc` says `24`. Do not replace the system Node installation. |
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

Start local PostgreSQL and Kafka. Both host ports stay on loopback. Do not use `docker compose down -v`.

```bash
cp infra/.env.example infra/.env   # if you do not already have a local file
docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres kafka
docker compose --env-file infra/.env -f infra/compose.yaml ps
docker inspect --format '{{json .HostConfig.PortBindings}}' streamforge-postgres-1
docker inspect --format '{{json .HostConfig.PortBindings}}' streamforge-kafka-1
```

`kafka-init` creates `telemetry.reading.v1` and `telemetry.reading.v1.dlt` if they are missing. It does not delete topics. The host client address is `127.0.0.1:9094`. Containers on the Compose network use `kafka:9092`. The controller listener is not published.

Run the API with the publisher off:

```bash
set -a && source infra/.env && set +a
export OUTBOX_PUBLISHER_ENABLED=false
./mvnw -pl backend/api spring-boot:run
```

The preserved database contains pending events from earlier milestones. Setting `OUTBOX_PUBLISHER_ENABLED=true` publishes those rows as well as new ones. Use a disposable database when you want to exercise publication without changing that data.

After the API has applied Flyway V3, run one Worker:

```bash
export KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9094
./mvnw -pl backend/worker spring-boot:run
```

The Worker does not run Flyway. It has no web server. Its consumer group is `streamforge-telemetry-worker`. `./mvnw -pl backend/worker package` writes a plain jar for tests and `streamforge-worker-0.1.0-SNAPSHOT-exec.jar` as the runnable archive.

Analytics reads require the device-admin key. Summary and series are under `/api/v1/analytics/devices/{deviceId}`. Readings are under `/api/v1/devices/{deviceId}/readings`. Leave `STREAMFORGE_ANALYTICS_REBUILD_ENABLED` false for the API process. A rebuild is a separate invocation that exits when it finishes. That invocation does not start the HTTP server, so it can run while the API is already listening:

```bash
export STREAMFORGE_ANALYTICS_REBUILD_ENABLED=true
export STREAMFORGE_ANALYTICS_REBUILD_FROM=2026-06-15T00:00:00Z
export STREAMFORGE_ANALYTICS_REBUILD_TO=2026-06-16T00:00:00Z
java -jar backend/api/target/streamforge-api-0.1.0-SNAPSHOT.jar
```

Resume that run with `STREAMFORGE_ANALYTICS_REBUILD_RUN_ID` and without `FROM` or `TO`. Do not point a rebuild at the preserved development database unless that history should be aggregated. The command does not delete telemetry, outbox rows, or receipts.

Read-only checks:

```bash
docker compose --env-file infra/.env -f infra/compose.yaml exec postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT status, count(*) FROM streamforge.outbox_events GROUP BY status;"
docker compose --env-file infra/.env -f infra/compose.yaml exec postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT count(*) FROM streamforge.processed_events;"
docker compose --env-file infra/.env -f infra/compose.yaml exec kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
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
- Application modules are `backend/api`, `backend/worker`, and `backend/simulator`. The simulator is a manual CLI. The Worker is a separate process and does not depend on the API jar.
- Package roots are `com.streamforge.api`, `com.streamforge.worker`, and `com.streamforge.simulator`.
- M3 device and telemetry routes require bearer API keys. Replay is not implemented. Kafka publication runs only when `OUTBOX_PUBLISHER_ENABLED=true`, and only one API process may have it enabled.
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

`V2__create_ingestion_tables.sql` creates `devices`, `telemetry_events`, and `outbox_events`. It is additive. Its checksum on databases that applied this file is `501121659`. Do not edit it.

## Flyway V3

`V3__create_processed_events.sql` creates `streamforge.processed_events`. The Worker inserts one row per accepted event after its database transaction commits. The API does not write this table during HTTP acceptance. V3 is additive. On the preserved local database its checksum is `39366715`. Do not repair history or delete the Compose volume to apply it.

## Flyway V4

`V4__outbox_claim_and_retry.sql` adds claim and retry columns to `outbox_events`. It does not rewrite existing statuses. Pending rows stay pending and become eligible for a claim. Published rows stay published. On a disposable database that applied this file, its checksum is `-1580136198`. Do not edit V1, V2, or V3.

A failed outbox row is requeued only with the statement in [M5](milestones/M5.md). Do not run it against the preserved development database unless that event should be published. Leave `OUTBOX_PUBLISHER_ENABLED` false there. The publisher claims one row at a time, up to 20 per cycle, with a 30-second lease.

## API keys and the simulator

Generate two different local keys and put them only in `infra/.env`:

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Device routes use `DEVICE_ADMIN_API_KEY`. Telemetry routes use `INGESTION_API_KEY`. Health does not require a key. Create a device before running the simulator. The simulator refuses a non-loopback base URL unless `--allow-service-host true` is set, and that flag allows only `http://api:<port>`. It sends at most 100 events and reads the ingestion key from the environment. It does not print the key.

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

`--clock now` is allowed only with `--mode valid`. It places every `occurredAt` in the previous six UTC hours, ending at the current second, so the events are inside the dashboard's Last 24 hours window and are not in the future. Use a new `--seed` for each live run. The same seed at a later clock can change `occurredAt`, change the canonical hash, and return HTTP 409. `--mode duplicate` stays on the fixed `2026-01-01T00:00:00Z` clock.

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
  worker/              # Independent Kafka consumer
  shared-contract/     # deferred until needed
frontend/              # local operations console
infra/                 # preserved Compose and the disposable demo Compose
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

## Frontend

Select Node.js 24, then install from the lockfile:

```bash
export PATH="$HOME/.local/node-v24.22.0-darwin-arm64/bin:$PATH"
cd frontend
node -v
npm ci
npm run dev
```

`npm run dev` listens on `127.0.0.1:5173` and proxies `/api` and `/actuator` to `http://127.0.0.1:8080`. Start the preserved API with the publisher off before using that proxy. The admin key is typed into the page. Do not put it in a Vite variable.

Other frontend commands:

```bash
npm run typecheck
npm run lint
npm test
npm run build
npm run e2e
```

`npm run e2e` is Playwright against the disposable demo, not against the Vite dev server. It requires `E2E_BASE_URL`, `E2E_ADMIN_KEY`, and `E2E_INGESTION_KEY`. It refuses ports 8080, 5173, 5432, and 9094.

## Disposable demo

This stack is project `streamforge-m7demo`. It does not attach the preserved volumes `streamforge_streamforge-postgres` or `streamforge_streamforge-kafka`.

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
cp infra/demo.env.example infra/.env.demo
# Replace both keys and the database password in infra/.env.demo.
set -a && source infra/.env.demo && set +a
docker compose --env-file infra/.env.demo -f infra/compose.demo.yaml build
docker compose --env-file infra/.env.demo -f infra/compose.demo.yaml up -d
```

Compose uses an exported shell variable ahead of `--env-file`. Source `infra/.env.demo` in the same shell so a key left over from the preserved project does not replace the demo key. Sourcing changes only that shell.

Open `http://127.0.0.1:4173`. The API is also on `127.0.0.1:18080` for a host-side simulator. PostgreSQL and Kafka publish no host ports. Inside the demo network the API uses `postgres` and `kafka:9092`, and `OUTBOX_PUBLISHER_ENABLED=true` applies only to that empty database.

One live simulator run, with a new seed:

```bash
docker compose --env-file infra/.env.demo -f infra/compose.demo.yaml --profile demo run --rm --no-deps simulator \
  --base-url http://api:8080 \
  --allow-service-host true \
  --device-id sensor-042 \
  --seed 2026101501 \
  --count 12 \
  --mode valid \
  --clock now
```

Stop the demo and keep its volumes:

```bash
docker compose --env-file infra/.env.demo -f infra/compose.demo.yaml stop
```

Reset only the disposable demo data, after you mean to delete it:

```bash
docker compose --env-file infra/.env.demo -f infra/compose.demo.yaml down -v
```

Do not add `-v` to a command whose project is `streamforge`. Do not point the demo file at the preserved volume names.

Build the four images without Compose:

```bash
docker build -f backend/api/Dockerfile -t streamforge-api:m7 .
docker build -f backend/worker/Dockerfile -t streamforge-worker:m7 .
docker build -f backend/simulator/Dockerfile -t streamforge-simulator:m7 .
docker build -f frontend/Dockerfile -t streamforge-web:m7 frontend
```

Image builds use `-DskipTests` because Testcontainers cannot run inside the image build. `./mvnw -B -ntp verify` remains the Java test gate.

## Related Documents

- [Architecture](architecture.md)
- [Testing Strategy](testing-strategy.md)
- [Known Issues and Risks](known-issues.md)
- [M2 Milestone](milestones/M2.md)
- [M3 Milestone](milestones/M3.md)
- [M4 Milestone](milestones/M4.md)
