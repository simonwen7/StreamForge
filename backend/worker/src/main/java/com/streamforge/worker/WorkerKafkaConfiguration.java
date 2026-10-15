package com.streamforge.worker;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class WorkerKafkaConfiguration {

    @Bean
    ReadingRecordParser readingRecordParser() {
        return new ReadingRecordParser();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${streamforge.worker.dead-letter-topic}") String deadLetterTopic,
            @Value("${streamforge.worker.retry-delay-ms}") long retryDelayMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setLogRecoveryRecord(false);
        recoverer.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        recoverer.setExceptionHeadersCreator((headers, exception, keyException, names) -> {
            Throwable cause = poisonCause(exception);
            String message = cause instanceof PoisonMessageException poison
                    ? poison.getMessage()
                    : "Processing failed.";
            headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, exception.getClass().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, cause.getClass().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryDelayMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.addNotRetryableExceptions(PoisonMessageException.class);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    private static Throwable poisonCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof PoisonMessageException) {
                return current;
            }
            current = current.getCause();
        }
        return exception.getCause() == null ? exception : exception.getCause();
    }
}
