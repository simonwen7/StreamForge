package com.streamforge.api.publication;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxPublicationRepository {

    private final JdbcClient jdbcClient;

    public OutboxPublicationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Claims one eligible pending row and commits that statement before the caller returns.
     * Acquiring the claim does not increment {@code attempt_count}.
     */
    public Optional<ClaimedReading> claimOne(long leaseSeconds) {
        Optional<ClaimToken> claimed = jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT outbox_id
                            FROM streamforge.outbox_events
                            WHERE status = 'pending'
                              AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())
                              AND (claimed_until IS NULL OR claimed_until <= clock_timestamp())
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE streamforge.outbox_events AS outbox
                        SET claim_token = gen_random_uuid(),
                            claimed_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
                        FROM candidate
                        WHERE outbox.outbox_id = candidate.outbox_id
                        RETURNING outbox.event_id, outbox.claim_token, outbox.attempt_count
                        """)
                .param("leaseSeconds", leaseSeconds)
                .query((resultSet, rowNumber) -> new ClaimToken(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("claim_token", UUID.class),
                        resultSet.getInt("attempt_count")))
                .optional();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimToken token = claimed.get();
        PendingReading reading = jdbcClient.sql("""
                        SELECT e.event_id, e.device_id, e.event_type, e.schema_version, e.occurred_at,
                               e.temperature_c, e.humidity_pct, e.battery_pct, e.received_at
                        FROM streamforge.telemetry_events e
                        WHERE e.event_id = :eventId
                        """)
                .param("eventId", token.eventId())
                .query(this::mapPending)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Claimed telemetry row was not visible."));
        return Optional.of(new ClaimedReading(reading, token.claimToken(), token.attemptCount()));
    }

    /**
     * Fenced start of a Kafka send. This is the only increment of {@code attempt_count}.
     * The lease is extended in the same statement so the send does not outlive ownership.
     */
    public Optional<Integer> beginAttempt(UUID eventId, UUID claimToken, long leaseSeconds) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET attempt_count = attempt_count + 1,
                            claimed_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
                        WHERE event_id = :eventId
                          AND claim_token = :claimToken
                          AND status = 'pending'
                        RETURNING attempt_count
                        """)
                .param("leaseSeconds", leaseSeconds)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .query(Integer.class)
                .optional();
    }

    public int renew(UUID eventId, UUID claimToken, long leaseSeconds) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET claimed_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
                        WHERE event_id = :eventId
                          AND claim_token = :claimToken
                          AND status = 'pending'
                        """)
                .param("leaseSeconds", leaseSeconds)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .update();
    }

    public int markPublished(UUID eventId, UUID claimToken) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET status = 'published',
                            published_at = clock_timestamp(),
                            claim_token = NULL,
                            claimed_until = NULL,
                            next_attempt_at = NULL
                        WHERE event_id = :eventId
                          AND claim_token = :claimToken
                          AND status = 'pending'
                        """)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .update();
    }

    public int scheduleRetry(UUID eventId, UUID claimToken, String errorCode, Duration delay) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET claim_token = NULL,
                            claimed_until = NULL,
                            next_attempt_at = clock_timestamp() + (:delayMillis * interval '1 millisecond'),
                            last_error_code = :errorCode
                        WHERE event_id = :eventId
                          AND claim_token = :claimToken
                          AND status = 'pending'
                        """)
                .param("delayMillis", delay.toMillis())
                .param("errorCode", errorCode)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .update();
    }

    public int markFailed(UUID eventId, UUID claimToken, String errorCode) {
        return jdbcClient.sql("""
                        UPDATE streamforge.outbox_events
                        SET status = 'failed',
                            claim_token = NULL,
                            claimed_until = NULL,
                            next_attempt_at = NULL,
                            last_error_code = :errorCode
                        WHERE event_id = :eventId
                          AND claim_token = :claimToken
                          AND status = 'pending'
                          AND published_at IS NULL
                        """)
                .param("errorCode", errorCode)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .update();
    }

    public int countPending() {
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM streamforge.outbox_events WHERE status = 'pending'
                        """)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    public OutboxDiagnostics diagnostics() {
        return jdbcClient.sql("""
                        SELECT
                            COUNT(*) FILTER (WHERE status = 'pending') AS pending,
                            COUNT(*) FILTER (
                                WHERE status = 'pending'
                                  AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())
                                  AND (claimed_until IS NULL OR claimed_until <= clock_timestamp())
                            ) AS eligible,
                            COUNT(*) FILTER (
                                WHERE status = 'pending'
                                  AND claim_token IS NOT NULL
                                  AND claimed_until > clock_timestamp()
                            ) AS active_claims,
                            COUNT(*) FILTER (
                                WHERE status = 'pending'
                                  AND claim_token IS NOT NULL
                                  AND claimed_until <= clock_timestamp()
                            ) AS expired_claims,
                            COUNT(*) FILTER (WHERE status = 'failed') AS failed,
                            COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at) FILTER (WHERE status = 'pending'))), 0) AS oldest_pending_age_seconds
                        FROM streamforge.outbox_events
                        """)
                .query((resultSet, rowNumber) -> new OutboxDiagnostics(
                        resultSet.getInt("pending"),
                        resultSet.getInt("eligible"),
                        resultSet.getInt("active_claims"),
                        resultSet.getInt("expired_claims"),
                        resultSet.getInt("failed"),
                        resultSet.getLong("oldest_pending_age_seconds")))
                .single();
    }

    private PendingReading mapPending(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PendingReading(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("device_id"),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant().atOffset(ZoneOffset.UTC).toInstant(),
                resultSet.getBigDecimal("temperature_c"),
                resultSet.getBigDecimal("humidity_pct"),
                resultSet.getBigDecimal("battery_pct"),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant());
    }

    private record ClaimToken(UUID eventId, UUID claimToken, int attemptCount) {
    }
}
