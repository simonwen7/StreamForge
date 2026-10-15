package com.streamforge.worker;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class WorkerTestSupport {

    static final String POSTGRES_IMAGE =
            "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";
    static final String KAFKA_IMAGE =
            "apache/kafka:4.2.1@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0";
    static final String TOPIC = "telemetry.reading.v1";
    static final String DEAD_LETTER_TOPIC = "telemetry.reading.v1.dlt";
    static final String OCCURRED_AT = "2026-01-01T00:00:00Z";
    static final String RECEIVED_AT = "2026-01-01T00:00:01Z";

    final PostgreSQLContainer postgres;
    final KafkaContainer kafka;
    final JdbcTemplate jdbc;

    private WorkerTestSupport(PostgreSQLContainer postgres, KafkaContainer kafka) {
        this.postgres = postgres;
        this.kafka = kafka;
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        this.jdbc = new JdbcTemplate(dataSource);
    }

    static WorkerTestSupport start(boolean autoCreateTopics) {
        PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
        postgres.start();
        KafkaContainer kafka = new KafkaContainer(
                DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));
        kafka.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", Boolean.toString(autoCreateTopics));
        kafka.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(migrationLocation())
                .schemas("streamforge")
                .defaultSchema("streamforge")
                .load()
                .migrate();
        return new WorkerTestSupport(postgres, kafka);
    }

    void close() {
        kafka.close();
        postgres.close();
    }

    void createTopics(String... names) throws Exception {
        try (AdminClient admin = admin()) {
            List<NewTopic> topics = new ArrayList<>();
            for (String name : names) {
                topics.add(new NewTopic(name, 1, (short) 1));
            }
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    UUID insertReading(String deviceId, String temperature) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO streamforge.devices (device_id) VALUES (?) ON CONFLICT DO NOTHING", deviceId);
        jdbc.update("""
                INSERT INTO streamforge.telemetry_events (
                    event_id, device_id, event_type, schema_version, occurred_at,
                    temperature_c, humidity_pct, battery_pct, canonical_hash, received_at
                ) VALUES (?::uuid, ?, 'telemetry.reading', 1, ?::timestamptz, ?::numeric, 48.2, 87.0,
                          decode(repeat('ab', 32), 'hex'), ?::timestamptz)
                """, eventId.toString(), deviceId, OCCURRED_AT, temperature, RECEIVED_AT);
        return eventId;
    }

    int receipts(UUID eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM streamforge.processed_events WHERE event_id = ?::uuid",
                Integer.class,
                eventId.toString());
        return count == null ? 0 : count;
    }

    void produce(String key, String value, String headerEventId) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, value);
            if (headerEventId != null) {
                record.headers().add(new RecordHeader(
                        ReadingRecordParser.EVENT_ID_HEADER,
                        headerEventId.getBytes(StandardCharsets.UTF_8)));
            }
            producer.send(record).get(15, TimeUnit.SECONDS);
        }
    }

    void produceValid(UUID eventId, String deviceId, String temperature) throws Exception {
        produce(deviceId, validJson(eventId, deviceId, temperature), eventId.toString());
    }

    List<ConsumerRecord<String, String>> deadLetters() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-inspect-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                var polled = consumer.poll(Duration.ofMillis(300));
                if (polled.isEmpty() && !records.isEmpty()) {
                    break;
                }
                polled.forEach(records::add);
            }
        }
        return records;
    }

    static String validJson(UUID eventId, String deviceId, String temperature) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + OCCURRED_AT + "\","
                + "\"measurements\":{"
                + "\"temperatureC\":" + temperature + ","
                + "\"humidityPct\":48.2,"
                + "\"batteryPct\":87.0},"
                + "\"receivedAt\":\"" + RECEIVED_AT + "\"}";
    }

    private static String migrationLocation() {
        Path moduleRelative = Path.of("..", "api", "src", "main", "resources", "db", "migration");
        if (Files.isDirectory(moduleRelative)) {
            return "filesystem:" + moduleRelative.toAbsolutePath();
        }
        Path rootRelative = Path.of("backend", "api", "src", "main", "resources", "db", "migration");
        if (Files.isDirectory(rootRelative)) {
            return "filesystem:" + rootRelative.toAbsolutePath();
        }
        throw new IllegalStateException("API migrations were not found from " + Path.of("").toAbsolutePath());
    }
}
