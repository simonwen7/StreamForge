-- Additive M6 derived hour statistics. V1 through V4 are immutable.
-- device_hour_stats is rebuilt from telemetry_events. It is not a source of truth.
-- max_source_received_at is the greatest received_at in that recomputed bucket.
-- It is not a completeness watermark and does not mean the hour is final.
-- next_chunk_start is the first UTC midnight that this run has not committed.
-- A day is committed only when its DELETE, INSERT, and checkpoint update commit together.
-- status becomes completed only in that same update, when next_chunk_start reaches effective_to.

CREATE TABLE streamforge.device_hour_stats (
    device_id varchar(64) NOT NULL REFERENCES streamforge.devices (device_id),
    bucket_start timestamptz NOT NULL,
    reading_count integer NOT NULL,
    temperature_min numeric NOT NULL,
    temperature_max numeric NOT NULL,
    temperature_sum numeric NOT NULL,
    humidity_sum numeric NOT NULL,
    battery_sum numeric NOT NULL,
    max_source_received_at timestamptz NOT NULL,
    PRIMARY KEY (device_id, bucket_start),
    CONSTRAINT device_hour_stats_count_positive CHECK (reading_count > 0),
    CONSTRAINT device_hour_stats_temperature_order CHECK (temperature_min <= temperature_max)
);

CREATE INDEX device_hour_stats_bucket_idx
    ON streamforge.device_hour_stats (bucket_start);

CREATE TABLE streamforge.analytics_rebuild_runs (
    run_id uuid PRIMARY KEY,
    device_id varchar(64) NULL REFERENCES streamforge.devices (device_id),
    requested_from timestamptz NOT NULL,
    requested_to timestamptz NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NOT NULL,
    next_chunk_start timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    chunks_committed integer NOT NULL DEFAULT 0,
    error_code varchar(64),
    CONSTRAINT analytics_rebuild_runs_status CHECK (status IN ('running', 'completed', 'failed')),
    CONSTRAINT analytics_rebuild_runs_requested_range CHECK (requested_from < requested_to),
    CONSTRAINT analytics_rebuild_runs_effective_range CHECK (effective_from < effective_to),
    CONSTRAINT analytics_rebuild_runs_chunks_nonnegative CHECK (chunks_committed >= 0),
    CONSTRAINT analytics_rebuild_runs_checkpoint CHECK (
        next_chunk_start >= effective_from AND next_chunk_start <= effective_to
    ),
    CONSTRAINT analytics_rebuild_runs_completed CHECK (
        status <> 'completed'
        OR (next_chunk_start = effective_to AND finished_at IS NOT NULL)
    ),
    CONSTRAINT analytics_rebuild_runs_running CHECK (
        status <> 'running' OR finished_at IS NULL
    )
);

CREATE INDEX analytics_rebuild_runs_status_started_idx
    ON streamforge.analytics_rebuild_runs (status, started_at DESC);
