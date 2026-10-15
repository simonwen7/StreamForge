package com.streamforge.worker;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetryListener.class);

    private final ReadingRecordParser parser;
    private final ProcessingService processingService;

    public TelemetryListener(ReadingRecordParser parser, ProcessingService processingService) {
        this.parser = parser;
        this.processingService = processingService;
    }

    @KafkaListener(
            id = "telemetry-reading",
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${streamforge.worker.topic}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        log.info(
                "Consuming topic={} partition={} offset={} key={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key());
        ReadingRecord reading = parser.parse(record);
        processingService.record(reading);
        acknowledgment.acknowledge();
    }
}
