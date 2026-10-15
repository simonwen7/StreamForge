package com.streamforge.worker;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000"
})
class WorkerDeadLetterFailureTests {

    private static WorkerTestSupport support;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        support = WorkerTestSupport.start(false);
        support.createTopics(WorkerTestSupport.TOPIC);
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
        registry.add("streamforge.worker.retry-delay-ms", () -> "1000");
    }

    @Test
    void failedDeadLetterPublicationDoesNotAcknowledgeOrSkipTheRecord() throws Exception {
        String deviceId = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID valid = support.insertReading(deviceId, "21.7");
        support.produce(deviceId, "{not-json", UUID.randomUUID().toString());
        support.produceValid(valid, deviceId, "21.7");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            try (AdminClient admin = support.admin()) {
                Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
                assertThat(topics).doesNotContain(WorkerTestSupport.DEAD_LETTER_TOPIC);
            }
        });
        Thread.sleep(Duration.ofSeconds(6));
        try (AdminClient admin = support.admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin
                    .listConsumerGroupOffsets("streamforge-telemetry-worker")
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            assertThat(offsets.get(new TopicPartition(WorkerTestSupport.TOPIC, 0))).isNull();
            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            assertThat(topics).doesNotContain(WorkerTestSupport.DEAD_LETTER_TOPIC);
        }
        assertThat(support.receipts(valid)).isZero();
    }
}
