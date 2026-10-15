package com.streamforge.api.publication;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "streamforge.publication.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration DATABASE_UPDATE_RETRY = Duration.ZERO;
    private static final long DIAGNOSTIC_INTERVAL_MS = 60_000;

    private final OutboxPublicationRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PublicationProperties properties;
    private final ReadingEncoder encoder;
    private final RetryDelay retryDelay;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private volatile long lastDiagnosticAtMs;

    public OutboxPublisher(
            OutboxPublicationRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            PublicationProperties properties,
            ReadingEncoder encoder,
            RetryDelay retryDelay) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.encoder = encoder;
        this.retryDelay = retryDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logPublisherEnabled() {
        int pending = repository.countPending();
        log.info(
                "Outbox publisher enabled for topic {}. Each cycle claims one row at a time, up to {} rows. "
                        + "Lease {} seconds. Transient failures stay pending. "
                        + "A crash after the broker acknowledgement can publish the record again. "
                        + "Pending rows, including events accepted before this process started, will be published. pendingCount={}",
                properties.getTopic(),
                properties.getMaxPerCycle(),
                properties.getLease().toSeconds(),
                pending);
    }

    @EventListener(ContextClosedEvent.class)
    public void stopClaiming() {
        accepting.set(false);
    }

    @Scheduled(
            fixedDelayString = "${streamforge.publication.poll-delay-ms:1000}",
            initialDelayString = "${streamforge.publication.poll-delay-ms:1000}")
    public void poll() {
        publishPending();
    }

    public int publishPending() {
        int published = 0;
        Set<java.util.UUID> claimedThisCycle = new HashSet<>();
        for (int index = 0; index < properties.getMaxPerCycle(); index++) {
            if (!accepting.get()) {
                break;
            }
            Optional<ClaimedReading> claimed;
            try {
                claimed = repository.claimOne(properties.getLease().toSeconds());
            } catch (RuntimeException ex) {
                log.warn("Outbox claim failed. reason={}", ex.getClass().getSimpleName());
                break;
            }
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedReading claim = claimed.get();
            if (!claimedThisCycle.add(claim.reading().eventId())) {
                release(claim.reading().eventId(), claim.claimToken(), "RETRY_DEFERRED", Duration.ZERO);
                break;
            }
            if (publishClaimed(claim)) {
                published++;
            }
        }
        logDiagnostics();
        return published;
    }

    private boolean publishClaimed(ClaimedReading claimed) {
        var reading = claimed.reading();
        ProducerRecord<String, String> record;
        try {
            record = encoder.record(properties.getTopic(), reading);
        } catch (PublicationPermanentException ex) {
            failPermanent(reading.eventId(), claimed.claimToken(), ex.errorCode());
            return false;
        }
        Optional<Integer> attempt = repository.beginAttempt(
                reading.eventId(), claimed.claimToken(), properties.getLease().toSeconds());
        if (attempt.isEmpty()) {
            log.warn("Lost outbox claim before send. eventId={}", reading.eventId());
            return false;
        }
        warnIfRepeated(reading.eventId(), attempt.get());
        org.springframework.kafka.support.SendResult<String, String> result;
        try {
            result = kafkaTemplate.send(record)
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            releaseRetryable(reading.eventId(), claimed.claimToken(), attempt.get(), "KAFKA_TIMEOUT", ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            releaseRetryable(reading.eventId(), claimed.claimToken(), attempt.get(), "KAFKA_SEND", ex);
            return false;
        } catch (ExecutionException | RuntimeException ex) {
            if (permanentKafka(ex)) {
                failPermanent(reading.eventId(), claimed.claimToken(), "CONTRACT_ENCODING");
                return false;
            }
            releaseRetryable(reading.eventId(), claimed.claimToken(), attempt.get(), "KAFKA_SEND", ex);
            return false;
        }
        try {
            int updated = repository.markPublished(reading.eventId(), claimed.claimToken());
            if (updated == 1) {
                log.info(
                        "Published eventId={} topic={} partition={} offset={} attempt={}",
                        reading.eventId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        attempt.get());
                return true;
            }
            log.warn("Lost outbox claim after broker acknowledgement. eventId={} updated={}", reading.eventId(), updated);
            return false;
        } catch (RuntimeException ex) {
            release(reading.eventId(), claimed.claimToken(), "DATABASE_UPDATE", DATABASE_UPDATE_RETRY);
            log.warn(
                    "Broker accepted eventId={} but the outbox update failed. reason={}",
                    reading.eventId(),
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    private void releaseRetryable(
            java.util.UUID eventId,
            java.util.UUID claimToken,
            int attempt,
            String code,
            Exception ex) {
        release(eventId, claimToken, code, retryDelay.next(attempt));
        log.warn(
                "Publication stays pending. eventId={} attempt={} code={} reason={}",
                eventId,
                attempt,
                code,
                ex.getClass().getSimpleName());
    }

    private void failPermanent(java.util.UUID eventId, java.util.UUID claimToken, String errorCode) {
        int updated = repository.markFailed(eventId, claimToken, errorCode);
        if (updated == 1) {
            log.warn("Outbox event failed permanently. eventId={} code={}", eventId, errorCode);
            return;
        }
        log.warn("Lost outbox claim before permanent failure was recorded. eventId={} code={}", eventId, errorCode);
    }

    private void release(java.util.UUID eventId, java.util.UUID claimToken, String errorCode, Duration delay) {
        int updated = repository.scheduleRetry(eventId, claimToken, errorCode, delay);
        if (updated == 0) {
            log.warn("Lost outbox claim before retry was scheduled. eventId={} code={}", eventId, errorCode);
        }
    }

    private void warnIfRepeated(java.util.UUID eventId, int attemptCount) {
        int interval = properties.getAttemptWarningInterval();
        if (interval > 0 && attemptCount >= interval && attemptCount % interval == 0) {
            log.warn(
                    "Outbox event is still retryable after repeated transient failures. eventId={} attempt={}",
                    eventId,
                    attemptCount);
        }
    }

    private void logDiagnostics() {
        long now = System.currentTimeMillis();
        if (now - lastDiagnosticAtMs < DIAGNOSTIC_INTERVAL_MS) {
            return;
        }
        OutboxDiagnostics diagnostics = repository.diagnostics();
        if (diagnostics.pending() == 0 && diagnostics.failed() == 0 && diagnostics.expiredClaims() == 0) {
            return;
        }
        lastDiagnosticAtMs = now;
        log.info(
                "Outbox diagnostics pending={} eligible={} activeClaims={} expiredClaims={} failed={} oldestPendingAgeSeconds={}",
                diagnostics.pending(),
                diagnostics.eligible(),
                diagnostics.activeClaims(),
                diagnostics.expiredClaims(),
                diagnostics.failed(),
                diagnostics.oldestPendingAgeSeconds());
    }

    private static boolean permanentKafka(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RecordTooLargeException || current instanceof SerializationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
