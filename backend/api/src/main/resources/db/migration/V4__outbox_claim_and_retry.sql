-- Additive M5 outbox claims and retry metadata. V1, V2, and V3 are immutable.
-- Existing pending and published rows stay valid. attempt_count starts at 0.
-- A claim is not a new status. Transient failures stay pending.

ALTER TABLE streamforge.outbox_events
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN last_error_code varchar(64);

ALTER TABLE streamforge.outbox_events
    ADD CONSTRAINT outbox_events_claim_pair CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    ADD CONSTRAINT outbox_events_attempt_nonnegative CHECK (attempt_count >= 0),
    ADD CONSTRAINT outbox_events_published_unclaimed CHECK (
        status <> 'published' OR claim_token IS NULL
    ),
    ADD CONSTRAINT outbox_events_failed_unclaimed CHECK (
        status <> 'failed' OR claim_token IS NULL
    );

CREATE INDEX outbox_events_claim_idx
    ON streamforge.outbox_events (created_at)
    WHERE status = 'pending';
