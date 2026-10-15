package com.streamforge.api.publication;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.password=testcontainers-managed",
        "streamforge.publication.enabled=true",
        "streamforge.publication.poll-delay-ms=3600000",
        "streamforge.publication.send-timeout=10s"
})
@Import(PostgresTestcontainersConfiguration.class)
@AutoConfigureMockMvc
class OutboxPublisherTests {

    private static final String KAFKA_IMAGE =
            "apache/kafka:4.2.1@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0";
    private static final String TOPIC = "telemetry.reading.v1";

    private static KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PublicationProperties properties;

    @MockitoSpyBean
    private ReadingEncoder encoder;

    @Autowired
    private RetryDelay retryDelay;

    @MockitoSpyBean
    private OutboxPublicationRepository repository;

    @BeforeAll
    static void startKafka() throws Exception {
        kafka = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
        kafka.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.close();
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers());
    }

    @Test
    void confirmedAcknowledgementMarksTheOutboxPublished() throws Exception {
        UUID eventId = insertPending("21.7");
        assertThat(publishedAt(eventId)).isNull();
        assertThat(publisher.publishPending()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = recordsFor(eventId);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        String deviceId = deviceId(eventId);
        assertThat(record.key()).isEqualTo(deviceId);
        assertThat(record.value()).contains("\"eventId\":\"" + eventId + "\"");
        assertThat(record.value()).contains("\"schemaVersion\":1");
        assertThat(record.value()).contains("\"measurements\":{");
        assertThat(record.value()).contains("\"temperatureC\":");
        assertThat(record.value()).doesNotContain("api-key");
        assertThat(new String(record.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
                .isEqualTo(eventId.toString());
        assertThat(outboxStatus(eventId)).isEqualTo("published");
        assertThat(publishedAt(eventId)).isNotNull();
    }

    @Test
    void failureAfterBrokerAcknowledgementLeavesTheRowPendingAndRetryCanDuplicate() throws Exception {
        UUID eventId = insertPending("18.0");
        doThrow(new IllegalStateException("outbox-update-failed")).when(repository).markPublished(any(), any());
        assertThat(publisher.publishPending()).isZero();
        assertThat(outboxStatus(eventId)).isEqualTo("pending");
        assertThat(publishedAt(eventId)).isNull();
        assertThat(recordsFor(eventId)).hasSize(1);

        reset(repository);
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outboxStatus(eventId)).isEqualTo("published");
        assertThat(publishedAt(eventId)).isNotNull();
        assertThat(recordsFor(eventId)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString())).isEqualTo(1);
    }

    @Test
    void permanentEncodingFailureKeepsTheEventAndReportsFailed() throws Exception {
        UUID eventId = insertPending("17.0");
        doThrow(new PublicationPermanentException("CONTRACT_ENCODING")).when(encoder).record(any(), any());
        try {
            assertThat(publisher.publishPending()).isZero();
            assertThat(outboxStatus(eventId)).isEqualTo("failed");
            assertThat(publishedAt(eventId)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    Integer.class,
                    eventId.toString())).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT claim_token FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                    Object.class,
                    eventId.toString())).isNull();
            mockMvc.perform(get("/api/v1/telemetry/readings/" + eventId)
                            .header("Authorization", "Bearer " + INGESTION))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publication").value("FAILED"))
                    .andExpect(jsonPath("$.acceptance").value("ACCEPTED"));
        } finally {
            reset(encoder);
        }
    }

    @Test
    void twoPublisherInstancesClaimOneRowOnce() throws Exception {
        UUID eventId = insertPending("16.0");
        OutboxPublisher second = new OutboxPublisher(repository, kafkaTemplate, properties, encoder, retryDelay);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> publishAfter(barrier, publisher));
            Future<Integer> other = pool.submit(() -> publishAfter(barrier, second));
            assertThat(first.get(30, TimeUnit.SECONDS) + other.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outboxStatus(eventId)).isEqualTo("published");
        assertThat(recordsFor(eventId)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString())).isEqualTo(1);
    }

    private static int publishAfter(CyclicBarrier barrier, OutboxPublisher publisher) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return publisher.publishPending();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private UUID insertPending(String temperature) {
        String deviceId = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private String outboxStatus(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                String.class,
                eventId.toString());
    }

    private Object publishedAt(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                Object.class,
                eventId.toString());
    }

    private String deviceId(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT device_id FROM streamforge.telemetry_events WHERE event_id = ?::uuid",
                String.class,
                eventId.toString());
    }

    private List<ConsumerRecord<String, String>> recordsFor(UUID eventId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "publisher-inspect-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && matched.isEmpty()) {
                consumer.poll(Duration.ofMillis(400)).forEach(record -> {
                    if (record.value() != null && record.value().contains(eventId.toString())) {
                        matched.add(record);
                    }
                });
            }
            if (!matched.isEmpty()) {
                long drain = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (System.nanoTime() < drain) {
                    consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                        if (record.value() != null && record.value().contains(eventId.toString())) {
                            matched.add(record);
                        }
                    });
                }
            }
        }
        return matched;
    }
}
