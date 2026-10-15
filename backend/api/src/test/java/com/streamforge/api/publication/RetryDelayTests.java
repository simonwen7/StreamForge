package com.streamforge.api.publication;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDelayTests {

    private final RetryDelay delay = new RetryDelay(Duration.ofSeconds(1), Duration.ofMinutes(5), 0.2, () -> 0.0);

    @Test
    void backoffGrowsAndStaysInsideTheCapAndJitter() {
        assertThat(delay.next(1)).isEqualTo(Duration.ofMillis(800));
        assertThat(new RetryDelay(Duration.ofSeconds(1), Duration.ofMinutes(5), 0.2, () -> 1.0).next(1))
                .isEqualTo(Duration.ofMillis(1200));
        assertThat(new RetryDelay(Duration.ofSeconds(1), Duration.ofMinutes(5), 0.2, () -> 0.5).next(1))
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(delay.next(2)).isEqualTo(Duration.ofMillis(1600));
        assertThat(delay.next(20).compareTo(Duration.ofMinutes(5))).isLessThanOrEqualTo(0);
        assertThat(delay.next(40)).isEqualTo(Duration.ofMillis(240_000));
    }
}
