-- M2 platform foundation only.
-- Telemetry, outbox, idempotency, and processing tables are deferred until M3.

CREATE SCHEMA IF NOT EXISTS streamforge;

CREATE TABLE streamforge.schema_info (
    id SMALLINT PRIMARY KEY,
    platform_milestone TEXT NOT NULL,
    notes TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO streamforge.schema_info (id, platform_milestone, notes)
VALUES (
    1,
    'M2',
    'Platform schema placeholder. Event ingestion, outbox, and read-model tables are deferred until M3 review.'
);
