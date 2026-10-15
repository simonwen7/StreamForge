const REPO = "https://github.com/simonwen7/StreamForge/blob/main";

const LINKS = [
  ["Ingestion and outbox insert", `${REPO}/backend/api/src/main/java/com/streamforge/api/telemetry/IngestionService.java`],
  ["Outbox publisher", `${REPO}/backend/api/src/main/java/com/streamforge/api/publication/OutboxPublisher.java`],
  ["Claim and fencing SQL", `${REPO}/backend/api/src/main/java/com/streamforge/api/publication/OutboxPublicationRepository.java`],
  ["Worker receipt", `${REPO}/backend/worker/src/main/java/com/streamforge/worker/ProcessingService.java`],
  ["Kafka listener", `${REPO}/backend/worker/src/main/java/com/streamforge/worker/TelemetryListener.java`],
  ["Dead-letter handler", `${REPO}/backend/worker/src/main/java/com/streamforge/worker/WorkerKafkaConfiguration.java`],
  ["Analytics SQL", `${REPO}/backend/api/src/main/java/com/streamforge/api/analytics/AnalyticsRepository.java`],
  ["Telemetry simulator", `${REPO}/backend/simulator/src/main/java/com/streamforge/simulator/TelemetrySimulator.java`],
] as const;

export function ArchitecturePage() {
  return (
    <article className="sf-architecture">
      <header className="sf-hero">
        <p className="sf-kicker">The Java system behind the simulation</p>
        <h1>What the repository actually runs</h1>
        <p>
          The public site is a browser-local model. The implementation is a Java 21 API, PostgreSQL 17, a single-node Kafka 4.2.1 broker, and an independent worker. Docker Compose runs that stack locally. Nothing on this page is an AWS deployment.
        </p>
      </header>
      <section className="sf-panel">
        <h2>Durable ingestion</h2>
        <p>
          <code>IngestionService.accept</code> inserts the telemetry row and the outbox row in one database transaction. A repeated identical event returns the existing row. The same event id with a different canonical payload is a conflict. An unknown device is not accepted.
        </p>
      </section>
      <section className="sf-panel">
        <h2>Transactional outbox</h2>
        <p>
          The publisher is off unless <code>OUTBOX_PUBLISHER_ENABLED=true</code>. It claims one pending row at a time, sends that record to Kafka, and marks the row published only after the broker acknowledges it. A send failure leaves the row pending and schedules a retry. <code>failed</code> is reserved for a record that cannot be encoded.
        </p>
      </section>
      <section className="sf-panel">
        <h2>Lease and fencing</h2>
        <p>
          The claim stores a token and a lease deadline. A later update includes that token, so a publisher that lost the lease cannot mark the row published or failed. Two overlapping publishers do not own the same unexpired claim. A crash after the broker acknowledgement and before the outbox update can still publish the record again.
        </p>
      </section>
      <section className="sf-panel">
        <h2>At-least-once delivery and the worker</h2>
        <p>
          Kafka delivery is at-least-once. The worker writes one <code>processed_events</code> receipt per event id in the same transaction that must commit before the offset is acknowledged. A second delivery finds that receipt and does not apply a second business effect. This is not exactly-once Kafka delivery.
        </p>
      </section>
      <section className="sf-panel">
        <h2>Dead letters</h2>
        <p>
          A payload that does not match the stored reading is poison. The worker does not retry it. The dead-letter publish has to succeed before the source record is acknowledged. A crash between those two steps can copy the dead letter. A poison delivery does not mark the outbox row failed.
        </p>
      </section>
      <section className="sf-panel">
        <h2>Analytics</h2>
        <p>
          Summary, series, and readings aggregate <code>telemetry_events</code>. They do not wait for a receipt and they do not read <code>device_hour_stats</code>. Buckets are UTC hours or days on a half-open range. A late <code>occurredAt</code> changes a bucket that was already queried. The hourly rebuild is a separate explicit command and is off by default.
        </p>
      </section>
      <section className="sf-panel">
        <h2>Limits that stay true</h2>
        <ul>
          <li>The demo broker is one node, replication factor 1, one partition.</li>
          <li>There is no highly available cloud deployment in this repository.</li>
          <li>The operations console keeps the device-admin key in memory for a local Docker demo. It is not a public identity system.</li>
          <li>The website you are using simulates those behaviors. It does not connect to PostgreSQL or Kafka.</li>
        </ul>
      </section>
      <section className="sf-panel">
        <h2>Source</h2>
        <ul className="sf-links">
          {LINKS.map(([label, href]) => (
            <li key={href}><a href={href}>{label}</a></li>
          ))}
        </ul>
      </section>
    </article>
  );
}
