package com.desafio.activation.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy maxJitter =
            new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(30), bound -> bound);

    @Test
    void ceilingGrowsExponentiallyUntilMaxDelay() {
        assertThat(maxJitter.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(maxJitter.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(maxJitter.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(maxJitter.delayAfter(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(maxJitter.delayAfter(60)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void jitterStaysWithinCeiling() {
        var policy = new RetryPolicy(new ProcessingProperties(5, Duration.ofSeconds(2), Duration.ofSeconds(30),
                Duration.ofSeconds(30), 10, 4));

        for (int i = 0; i < 200; i++) {
            assertThat(policy.delayAfter(3)).isBetween(Duration.ZERO, Duration.ofSeconds(8));
        }
    }

    @Test
    void retriesStopAtMaxAttempts() {
        assertThat(maxJitter.canRetry(4)).isTrue();
        assertThat(maxJitter.canRetry(5)).isFalse();
    }
}
