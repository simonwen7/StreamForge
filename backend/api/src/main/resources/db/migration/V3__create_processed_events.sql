-- Additive M4 processing receipts. V1 and V2 are immutable.
-- One row per accepted event. The Worker inserts this after it commits processing.
-- The API does not write this table during HTTP acceptance.

CREATE TABLE streamforge.processed_events (
    event_id uuid PRIMARY KEY REFERENCES streamforge.telemetry_events (event_id),
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
