-- Additive M3 ingestion schema. V1 is immutable.
-- Outbox status values include published and failed so M4 can record publication
-- without a constraint change. M3 inserts only pending rows with published_at null.

CREATE TABLE streamforge.devices (
    device_id varchar(64) PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT devices_device_id_format CHECK (device_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')
);

CREATE TABLE streamforge.telemetry_events (
    event_id uuid PRIMARY KEY,
    device_id varchar(64) NOT NULL REFERENCES streamforge.devices (device_id),
    event_type varchar(32) NOT NULL,
    schema_version smallint NOT NULL,
    occurred_at timestamptz NOT NULL,
    temperature_c numeric NOT NULL,
    humidity_pct numeric NOT NULL,
    battery_pct numeric NOT NULL,
    canonical_hash bytea NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT telemetry_events_not_nil CHECK (event_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT telemetry_events_type CHECK (event_type = 'telemetry.reading'),
    CONSTRAINT telemetry_events_version CHECK (schema_version = 1),
    CONSTRAINT telemetry_events_temperature CHECK (
        temperature_c >= -40 AND temperature_c <= 125
        AND scale(temperature_c) BETWEEN 0 AND 3
    ),
    CONSTRAINT telemetry_events_humidity CHECK (
        humidity_pct >= 0 AND humidity_pct <= 100
        AND scale(humidity_pct) BETWEEN 0 AND 3
    ),
    CONSTRAINT telemetry_events_battery CHECK (
        battery_pct >= 0 AND battery_pct <= 100
        AND scale(battery_pct) BETWEEN 0 AND 3
    ),
    CONSTRAINT telemetry_events_hash_length CHECK (octet_length(canonical_hash) = 32)
);

CREATE INDEX telemetry_events_device_occurred_idx
    ON streamforge.telemetry_events (device_id, occurred_at DESC);

CREATE TABLE streamforge.outbox_events (
    outbox_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL UNIQUE REFERENCES streamforge.telemetry_events (event_id),
    status varchar(16) NOT NULL DEFAULT 'pending',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    CONSTRAINT outbox_events_status CHECK (status IN ('pending', 'published', 'failed')),
    CONSTRAINT outbox_events_publication CHECK (
        (status = 'pending' AND published_at IS NULL)
        OR (status = 'published' AND published_at IS NOT NULL)
        OR (status = 'failed')
    )
);

CREATE INDEX outbox_events_unpublished_idx
    ON streamforge.outbox_events (created_at)
    WHERE published_at IS NULL;
