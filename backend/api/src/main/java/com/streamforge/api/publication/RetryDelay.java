package com.streamforge.api.publication;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class RetryDelay {

    private final Duration base;
    private final Duration max;
    private final double jitterFraction;
    private final DoubleSupplier unit;

    @Autowired
    public RetryDelay(PublicationProperties properties) {
        this(properties.getRetryBase(), properties.getRetryMax(), properties.getJitterFraction(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    RetryDelay(Duration base, Duration max, double jitterFraction, DoubleSupplier unit) {
        this.base = base;
        this.max = max;
        this.jitterFraction = jitterFraction;
        this.unit = unit;
    }

    public Duration next(int attemptCount) {
        int steps = Math.max(0, attemptCount - 1);
        double multiplier = Math.pow(2, Math.min(steps, 16));
        double seconds = Math.min(base.toMillis() / 1000.0 * multiplier, max.toMillis() / 1000.0);
        double sample = Math.clamp(unit.getAsDouble(), 0.0, 1.0);
        double factor = 1 + ((sample * 2) - 1) * jitterFraction;
        long millis = Math.max(0, Math.round(seconds * factor * 1000));
        return Duration.ofMillis(millis);
    }
}
