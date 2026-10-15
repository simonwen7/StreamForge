package com.streamforge.api.publication;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "streamforge.publication.enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxPublicationRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PublicationProperties properties;

    public OutboxPublisher(
            OutboxPublicationRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            PublicationProperties properties) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSingleInstanceLimit() {
        int pending = repository.countPending();
        log.info(
                "Outbox publisher enabled for topic {}. M4 supports one publisher instance. "
                        + "A second API process with the publisher enabled can publish duplicates. "
                        + "Pending rows, including events accepted before this process started, will be published. pendingCount={}",
                properties.getTopic(),
                pending);
    }

    @Scheduled(
            fixedDelayString = "${streamforge.publication.poll-delay-ms:1000}",
            initialDelayString = "${streamforge.publication.poll-delay-ms:1000}")
    public void poll() {
        publishPending();
    }

    public int publishPending() {
        List<PendingReading> pending = repository.findPending(properties.getBatchSize());
        if (pending.isEmpty()) {
            log.debug("No pending outbox rows.");
            return 0;
        }
        log.info("Publishing pending outbox batch size={}", pending.size());
        int published = 0;
        for (PendingReading reading : pending) {
            if (!publishOne(reading)) {
                break;
            }
            published++;
        }
        return published;
    }

    private boolean publishOne(PendingReading reading) {
        ProducerRecord<String, String> record = ReadingMessageEncoder.record(properties.getTopic(), reading);
        try {
            var result = kafkaTemplate.send(record)
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            int updated = repository.markPublished(reading.eventId());
            if (updated == 1) {
                log.info(
                        "Published eventId={} topic={} partition={} offset={}",
                        reading.eventId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                return true;
            }
            log.warn(
                    "Kafka accepted eventId={} but the outbox row was no longer pending. updated={}",
                    reading.eventId(),
                    updated);
            return true;
        } catch (TimeoutException | ExecutionException ex) {
            log.warn(
                    "Publication failed and the outbox row stays pending. eventId={} reason={}",
                    reading.eventId(),
                    ex.getClass().getSimpleName());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Publication interrupted. eventId={} stays pending.", reading.eventId());
            return false;
        } catch (RuntimeException ex) {
            log.warn(
                    "Publication did not finish. eventId={} stays pending. reason={}",
                    reading.eventId(),
                    ex.getClass().getSimpleName());
            return false;
        }
    }
}
