package com.streamforge.api.publication;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

@Component
public class ReadingEncoder {

    public ProducerRecord<String, String> record(String topic, PendingReading reading) {
        return ReadingMessageEncoder.record(topic, reading);
    }
}
