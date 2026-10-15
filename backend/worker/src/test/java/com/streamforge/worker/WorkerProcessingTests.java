package com.streamforge.worker;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
class WorkerProcessingTests {

    private static WorkerTestSupport support;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoSpyBean
    private ProcessingRepository processingRepository;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        support = WorkerTestSupport.start(false);
        support.createTopics(WorkerTestSupport.TOPIC, WorkerTestSupport.DEAD_LETTER_TOPIC);
    }

    @AfterAll
    static void stopInfrastructure() {
        if (support != null) {
            support.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> support.postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> support.postgres.getUsername());
        registry.add("spring.datasource.password", () -> support.postgres.getPassword());
        registry.add("spring.kafka.bootstrap-servers", () -> support.kafka.getBootstrapServers());
        registry.add("streamforge.worker.retry-delay-ms", () -> "200");
    }

    @Test
    void workerContextStartsWithoutAWebServerOrFlyway() {
        assertThat(registry.getListenerContainer("telemetry-reading").isRunning()).isTrue();
        assertThat(applicationContext.getBeanNamesForType(org.flywaydb.core.Flyway.class)).isEmpty();
        assertThat(applicationContext.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
    }

    @AfterEach
    void resetRepositorySpy() {
        reset(processingRepository);
    }

    @Test
    void validRecordCreatesOneReceiptAndADuplicateDoesNotCreateAnother() throws Exception {
        String deviceId = deviceId();
        UUID eventId = support.insertReading(deviceId, "21.7");
        support.produceValid(eventId, deviceId, "21.7");
        await().atMost(Duration.ofSeconds(30)).until(() -> support.receipts(eventId) == 1);

        support.produceValid(eventId, deviceId, "21.7");
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8))
                .until(() -> support.receipts(eventId) == 1);
    }

    @Test
    void receiptAlreadyCommittedIsNotDuplicatedWhenTheRecordIsDeliveredAgain() throws Exception {
        String deviceId = deviceId();
        UUID eventId = support.insertReading(deviceId, "21.7");
        support.jdbc.update(
                "INSERT INTO streamforge.processed_events (event_id) VALUES (?::uuid)",
                eventId.toString());
        Long before = committedOffset(WorkerTestSupport.TOPIC);
        support.produceValid(eventId, deviceId, "21.7");
        long expected = before == null ? 1 : before + 1;
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Long offset = committedOffset(WorkerTestSupport.TOPIC);
            return offset != null && offset >= expected;
        });
        assertThat(support.receipts(eventId)).isEqualTo(1);
    }

    @Test
    void databaseFailureDoesNotAcknowledgeUntilTheReceiptCommits() throws Exception {
        String deviceId = deviceId();
        UUID eventId = support.insertReading(deviceId, "21.7");
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (eventId.equals(invocation.getArgument(0)) && failures.compareAndSet(0, 1)) {
                holding.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                throw new DataAccessResourceFailureException("temporary");
            }
            return invocation.callRealMethod();
        }).when(processingRepository).findTelemetry(any());

        Long before = committedOffset(WorkerTestSupport.TOPIC);
        support.produceValid(eventId, deviceId, "21.7");
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(support.receipts(eventId)).isZero();
        assertThat(committedOffset(WorkerTestSupport.TOPIC)).isEqualTo(before);
        release.countDown();
        await().atMost(Duration.ofSeconds(30)).until(() -> support.receipts(eventId) == 1);
        assertThat(deadLetterValues()).doesNotContain(WorkerTestSupport.validJson(eventId, deviceId, "21.7"));
    }

    @Test
    void poisonRecordsAreIsolatedAndALaterValidRecordIsProcessed() throws Exception {
        String deviceId = deviceId();
        UUID missing = UUID.randomUUID();
        support.produce(deviceId, "{not-json", missing.toString());
        support.produce(
                deviceId,
                WorkerTestSupport.validJson(missing, deviceId, "21.7").replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                missing.toString());
        support.produceValid(missing, deviceId, "21.7");

        UUID mismatched = support.insertReading(deviceId, "21.7");
        support.produceValid(mismatched, deviceId, "22.0");

        UUID valid = support.insertReading(deviceId, "19.5");
        support.produceValid(valid, deviceId, "19.5");

        await().atMost(Duration.ofSeconds(40)).until(() -> support.receipts(valid) == 1);
        assertThat(support.receipts(missing)).isZero();
        assertThat(support.receipts(mismatched)).isZero();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            String values = String.join("\n", deadLetterValues());
            assertThat(values).contains("{not-json");
            assertThat(values).contains("\"schemaVersion\":2");
            assertThat(values).contains(mismatched.toString());
            assertThat(values).contains(missing.toString());
            assertThat(values).doesNotContain("POSTGRES_PASSWORD");
        });
        ConsumerRecord<String, String> malformed = support.deadLetters().stream()
                .filter(record -> "{not-json".equals(record.value()))
                .findFirst()
                .orElseThrow();
        String exceptionMessage = header(malformed, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        assertThat(exceptionMessage).contains("not valid JSON");
        assertThat(header(malformed, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(WorkerTestSupport.TOPIC);
        assertThat(header(malformed, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("PoisonMessageException");
    }

    private Long committedOffset(String topic) {
        try (AdminClient admin = support.admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin
                    .listConsumerGroupOffsets("streamforge-telemetry-worker")
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            OffsetAndMetadata metadata = offsets.get(new TopicPartition(topic, 0));
            return metadata == null ? null : metadata.offset();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private java.util.List<String> deadLetterValues() {
        return support.deadLetters().stream().map(ConsumerRecord::value).toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static String deviceId() {
        return "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
