package com.streamforge.api.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs only when the rebuild flag is explicitly enabled. Normal API startup does not create this bean.
 */
@Component
@ConditionalOnProperty(name = "streamforge.analytics.rebuild.enabled", havingValue = "true")
class AnalyticsRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRebuildRunner.class);

    private final AnalyticsRebuild rebuild;
    private final AnalyticsRebuildProperties properties;
    private final ConfigurableApplicationContext context;

    AnalyticsRebuildRunner(
            AnalyticsRebuild rebuild,
            AnalyticsRebuildProperties properties,
            ConfigurableApplicationContext context) {
        this.rebuild = rebuild;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = exitCode(invoke());
        log.info("Rebuild command finished. exitCode={}", code);
        int exit = SpringApplication.exit(context, () -> code);
        System.exit(exit);
    }

    private RebuildResult invoke() {
        boolean hasRun = properties.getRunId() != null && !properties.getRunId().isBlank();
        boolean hasRange = notBlank(properties.getFrom()) || notBlank(properties.getTo());
        if (hasRun && hasRange) {
            log.warn("Rebuild command received both a run id and a range.");
            return RebuildResult.rejected(null, "AMBIGUOUS_COMMAND");
        }
        if (hasRun) {
            try {
                return rebuild.resume(UUID.fromString(properties.getRunId().trim()));
            } catch (IllegalArgumentException ex) {
                return RebuildResult.rejected(null, "UNKNOWN_RUN");
            }
        }
        if (!notBlank(properties.getFrom()) || !notBlank(properties.getTo())) {
            log.warn("Rebuild command requires from and to, or a run id.");
            return RebuildResult.rejected(null, "INVALID_RANGE");
        }
        String deviceId = notBlank(properties.getDeviceId()) ? properties.getDeviceId().trim() : null;
        try {
            Instant from = AnalyticsRequests.timestamp(properties.getFrom().trim(), "from");
            Instant to = AnalyticsRequests.timestamp(properties.getTo().trim(), "to");
            return rebuild.start(deviceId, from, to);
        } catch (RuntimeException ex) {
            log.warn("Rebuild command rejected the range. reason={}", ex.getClass().getSimpleName());
            return RebuildResult.rejected(null, "INVALID_RANGE");
        }
    }

    private static int exitCode(RebuildResult result) {
        return switch (result.outcome()) {
            case COMPLETED, ALREADY_COMPLETED -> 0;
            case LOCK_NOT_ACQUIRED -> 2;
            case FAILED, REJECTED -> 1;
        };
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
