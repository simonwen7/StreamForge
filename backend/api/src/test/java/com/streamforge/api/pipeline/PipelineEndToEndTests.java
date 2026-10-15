package com.streamforge.api.pipeline;

import com.streamforge.api.support.PostgresTestcontainersConfiguration;
import com.streamforge.worker.StreamForgeWorkerApplication;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.password=testcontainers-managed",
                "streamforge.publication.enabled=true",
                "streamforge.publication.poll-delay-ms=500",
                "streamforge.publication.send-timeout=10s"
        })
@Import(PostgresTestcontainersConfiguration.class)
class PipelineEndToEndTests {

    private static final String ADMIN = "test-device-admin-key-0123456789abcd";
    private static final String INGESTION = "test-ingestion-key-0123456789abcdef";
    private static final String KAFKA_IMAGE =
            "apache/kafka:4.2.1@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0";

    private static KafkaContainer kafka;
    private static ConfigurableApplicationContext worker;

    @LocalServerPort
    private int port;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startKafka() throws Exception {
        kafka = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
        kafka.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("telemetry.reading.v1", 1, (short) 1),
                    new NewTopic("telemetry.reading.v1.dlt", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stop() {
        if (worker != null) {
            worker.close();
        }
        if (kafka != null) {
            kafka.close();
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers());
    }

    @Test
    void httpAcceptancePublishesToKafkaAndTheWorkerRecordsOneReceipt() throws Exception {
        startWorker();
        String deviceId = "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID eventId = UUID.randomUUID();
        String body = reading(eventId, deviceId);

        HttpResponse<String> created = post("/api/v1/devices", "{\"deviceId\":\"" + deviceId + "\"}", ADMIN);
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> accepted = post("/api/v1/telemetry/readings", body, INGESTION);
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body()).contains("\"publication\":\"PENDING\"");
        assertThat(accepted.body()).contains("\"processing\":\"NOT_STARTED\"");
        assertThat(count("telemetry_events", eventId)).isEqualTo(1);
        assertThat(count("outbox_events", eventId)).isEqualTo(1);
        assertThat(count("processed_events", eventId)).isZero();

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            HttpResponse<String> current = get("/api/v1/telemetry/readings/" + eventId, INGESTION);
            assertThat(current.statusCode()).isEqualTo(200);
            assertThat(current.body()).contains("\"publication\":\"PUBLISHED\"");
            assertThat(current.body()).contains("\"processing\":\"PROCESSED\"");
            assertThat(current.body()).contains("\"processedAt\"");
        });

        HttpResponse<String> duplicate = post("/api/v1/telemetry/readings", body, INGESTION);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(count("telemetry_events", eventId)).isEqualTo(1);
        assertThat(count("outbox_events", eventId)).isEqualTo(1);
        assertThat(count("processed_events", eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM streamforge.outbox_events WHERE event_id = ?::uuid",
                String.class,
                eventId.toString())).isEqualTo("published");
    }

    private void startWorker() {
        if (worker != null) {
            return;
        }
        SpringApplication application = new SpringApplication(StreamForgeWorkerApplication.class);
        worker = application.run(
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--spring.kafka.consumer.group-id=streamforge-telemetry-worker",
                "--spring.kafka.consumer.auto-offset-reset=earliest",
                "--spring.kafka.consumer.enable-auto-commit=false",
                "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.listener.ack-mode=manual_immediate",
                "--spring.kafka.producer.acks=all",
                "--spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--spring.kafka.admin.auto-create=false",
                "--streamforge.worker.topic=telemetry.reading.v1",
                "--streamforge.worker.dead-letter-topic=telemetry.reading.v1.dlt",
                "--streamforge.worker.retry-delay-ms=200");
    }

    private HttpResponse<String> post(String path, String body, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String reading(UUID eventId, String deviceId) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"measurements\":{\"temperatureC\":21.7,\"humidityPct\":48.2,\"batteryPct\":87.0}}";
    }

    private int count(String table, UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streamforge." + table + " WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
        return count == null ? 0 : count;
    }
}
