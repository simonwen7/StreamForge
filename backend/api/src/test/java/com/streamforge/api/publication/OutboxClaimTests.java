package com.streamforge.api.publication;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.password=testcontainers-managed",
        "streamforge.publication.enabled=false"
})
@Import(PostgresTestcontainersConfiguration.class)
class OutboxClaimTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublicationRepository repository;

    @AfterEach
    void keepAbandonedClaimsOutOfLaterTests() {
        jdbcTemplate.update("""
                UPDATE streamforge.outbox_events
                SET claimed_until = clock_timestamp() + interval '1 hour'
                WHERE status = 'pending' AND claim_token IS NOT NULL
                """);
    }

    @Test
    void twoClaimersTakeOneRowAndDifferentRowsStayDistinct() throws Exception {
        UUID only = insertPending("21.7");
        List<Optional<ClaimedReading>> raced = raceClaims(2);
        List<ClaimedReading> won = raced.stream().flatMap(Optional::stream).toList();
        assertThat(won).hasSize(1);
        assertThat(won.get(0).reading().eventId()).isEqualTo(only);
        assertThat(won.get(0).attemptCount()).isZero();
        assertThat(activeClaims()).isEqualTo(1);
        assertThat(idleInTransaction()).isZero();

        UUID left = insertPending("18.0");
        UUID right = insertPending("19.0");
        List<Optional<ClaimedReading>> both = raceClaims(2);
        List<UUID> ids = both.stream().flatMap(Optional::stream).map(claim -> claim.reading().eventId()).toList();
        assertThat(ids).containsExactlyInAnyOrder(left, right);
    }

    @Test
    void expiredClaimCanBeReclaimedAndTheOldTokenIsFenced() {
        UUID eventId = insertPending("15.0");
        ClaimedReading first = repository.claimOne(30).orElseThrow();
        assertThat(repository.beginAttempt(eventId, first.claimToken(), 30)).contains(1);
        jdbcTemplate.update(
                "UPDATE streamforge.outbox_events SET claimed_until = clock_timestamp() - interval '1 second' WHERE event_id = ?::uuid",
                eventId.toString());

        ClaimedReading second = repository.claimOne(30).orElseThrow();
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(second.attemptCount()).isEqualTo(1);

        assertThat(repository.markPublished(eventId, first.claimToken())).isZero();
        assertThat(repository.scheduleRetry(eventId, first.claimToken(), "KAFKA_TIMEOUT", Duration.ofSeconds(5))).isZero();
        assertThat(repository.renew(eventId, first.claimToken(), 30)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_token FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                UUID.class,
                eventId.toString())).isEqualTo(second.claimToken());

        assertThat(repository.beginAttempt(eventId, second.claimToken(), 30)).contains(2);
        assertThat(repository.markPublished(eventId, second.claimToken())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                String.class,
                eventId.toString())).isEqualTo("published");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_token FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Object.class,
                eventId.toString())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Boolean.class,
                eventId.toString())).isTrue();
    }

    @Test
    void retryDelayHidesTheRowUntilItIsDueAndRequeueRestoresOnlyFailedRows() {
        UUID waiting = insertPending("14.0");
        ClaimedReading claimed = repository.claimOne(30).orElseThrow();
        assertThat(repository.beginAttempt(waiting, claimed.claimToken(), 30)).contains(1);
        assertThat(repository.scheduleRetry(waiting, claimed.claimToken(), "KAFKA_TIMEOUT", Duration.ofMinutes(5)))
                .isEqualTo(1);
        assertThat(repository.claimOne(30).map(claim -> claim.reading().eventId()).orElse(null)).isNotEqualTo(waiting);
        jdbcTemplate.update(
                "UPDATE streamforge.outbox_events SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE event_id = ?::uuid",
                waiting.toString());
        assertThat(repository.claimOne(30).orElseThrow().reading().eventId()).isEqualTo(waiting);

        UUID failed = insertPending("13.0");
        ClaimedReading failedClaim = repository.claimOne(30).orElseThrow();
        assertThat(failedClaim.reading().eventId()).isEqualTo(failed);
        assertThat(repository.markFailed(failed, failedClaim.claimToken(), "CONTRACT_ENCODING")).isEqualTo(1);
        assertThat(repository.claimOne(30).map(claim -> claim.reading().eventId()).orElse(null)).isNotEqualTo(failed);

        int requeued = jdbcTemplate.update("""
                UPDATE streamforge.outbox_events
                SET status = 'pending',
                    claim_token = NULL,
                    claimed_until = NULL,
                    next_attempt_at = NULL,
                    last_error_code = NULL,
                    attempt_count = 0
                WHERE event_id = ?::uuid
                  AND status = 'failed'
                  AND published_at IS NULL
                """, failed.toString());
        assertThat(requeued).isEqualTo(1);
        assertThat(repository.claimOne(30).orElseThrow().reading().eventId()).isEqualTo(failed);

        UUID published = insertPending("12.0");
        ClaimedReading publishedClaim = repository.claimOne(30).orElseThrow();
        repository.markPublished(published, publishedClaim.claimToken());
        int ignored = jdbcTemplate.update("""
                UPDATE streamforge.outbox_events
                SET status = 'pending',
                    claim_token = NULL,
                    claimed_until = NULL,
                    next_attempt_at = NULL,
                    last_error_code = NULL,
                    attempt_count = 0
                WHERE event_id = ?::uuid
                  AND status = 'failed'
                  AND published_at IS NULL
                """, published.toString());
        assertThat(ignored).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                String.class,
                published.toString())).isEqualTo("published");
    }

    private List<Optional<ClaimedReading>> raceClaims(int threads) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<ClaimedReading>>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return repository.claimOne(30);
                }));
            }
            List<Optional<ClaimedReading>> results = new ArrayList<>();
            for (Future<Optional<ClaimedReading>> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID insertPending(String temperature) {
        String deviceId = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO streamforge.devices (device_id) VALUES (?)", deviceId);
        jdbcTemplate.update("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash, received_at
                ) VALUES (?::uuid, ?, 'telemetry.reading', 1, '2026-01-01T00:00:00Z', ?::numeric, 48.2, 87.0,
                          decode(repeat('ab', 32), 'hex'), '2026-01-01T00:00:01Z')
                """, eventId.toString(), deviceId, temperature);
        jdbcTemplate.update(
                "INSERT INTO streamforge.outbox_events (event_id, status) VALUES (?::uuid, 'pending')",
                eventId.toString());
        return eventId;
    }

    private int activeClaims() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM streamforge.outbox_events
                WHERE claim_token IS NOT NULL AND claimed_until > clock_timestamp()
                """, Integer.class);
    }

    private int idleInTransaction() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND state = 'idle in transaction'
                  AND pid <> pg_backend_pid()
                """, Integer.class);
    }
}
