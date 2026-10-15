# Portfolio notes

These notes describe the repository as it exists. They do not claim a verified Vercel URL, a measured production throughput, or an AWS deployment.

## Project description

StreamForge is a telemetry pipeline that commits an accepted reading and an outbox row in one PostgreSQL transaction, publishes to Kafka at least once, and records one worker receipt so a duplicate delivery does not apply twice. A public site lets an interviewer operate a browser-local model of that pipeline. The Java services, Docker demo, and tests are in the same repository.

## Resume bullets

- Built a Java 21 / Spring Boot ingestion API that commits telemetry and a transactional outbox row together, and rejects a reused event id when the payload differs.
- Implemented a lease-fenced outbox publisher and a Kafka worker that writes one PostgreSQL receipt per event, with poison messages dead-lettered before the source record is acknowledged.
- Queried accepted telemetry for UTC hour and day analytics, including late events that change an earlier bucket, separate from worker completion.
- Shipped a React / TypeScript simulation of ingestion, broker loss, worker loss, duplicate delivery, and dead letters, built as a static site with no API key.
- Kept the Docker Compose demo, Flyway migrations V1–V5, and GitHub Actions for the Java tests, the frontend, and the disposable full-stack browser tests.

## Two-bullet version

- Designed a Spring Boot, PostgreSQL, and Kafka telemetry pipeline with a transactional outbox, at-least-once publication, and idempotent worker receipts.
- Added a public React simulation of those failure modes and preserved the local Docker demo and automated tests.

## Full-stack emphasis

The same repository contains the Spring API, the worker, a React operations console, and a static interview site. The console talks to the API only in the live build. The public build does not.

## Backend emphasis

The durable part is the database transaction, the claim token on the outbox, and the receipt primary key. Kafka is the transport. Analytics do not use the receipt table.

## Website blurb

StreamForge shows how an event stays accepted when the broker is down, and how a second Kafka delivery does not double the measurement. The interactive page runs in the browser. The implementation is Java, PostgreSQL, and Kafka.

## Thirty seconds

StreamForge accepts device readings into PostgreSQL with an outbox row in the same transaction. A publisher sends them to Kafka, and a worker records one receipt so duplicates do not apply twice. The site you can click is a simulation of that pipeline. The Java code and Docker demo are in the repo.

## Two minutes

Start with one event. It is accepted before it is processed. Pause the broker and the pending count grows, because ingestion already committed. Restore the broker and the worker writes a receipt. Replay the delivery and the accepted count stays the same. Analytics read the accepted rows, so they move at ingestion, not at the receipt. The real publisher claims one row with a lease token. The real worker dead-letters a payload that does not match the stored reading, and it acknowledges the source only after that dead-letter send. The local demo is one Kafka broker. Delivery is at-least-once.

## Simulation versus the backend

The public website is an interactive browser-local simulation. The implementation is the Java API, PostgreSQL, Kafka, and the worker. Docker and CI exercise that implementation. The simulation is there so a reviewer can see the behavior without installing the stack. It is not a deployed Kafka cluster.
