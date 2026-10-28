package com.desafio.activation.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/** Exponential backoff with full jitter, bounded by a maximum number of attempts. */
@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final LongUnaryOperator random;

    @Autowired
    public RetryPolicy(ProcessingProperties properties) {
        this(properties.maxAttempts(), properties.retryBaseDelay(), properties.retryMaxDelay(),
                bound -> ThreadLocalRandom.current().nextLong(bound + 1));
    }

    RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, LongUnaryOperator random) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.random = random;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /** Random delay in [0, min(maxDelay, baseDelay * 2^(attempt - 1))]. */
    public Duration delayAfter(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * (1L << exponent));
        return Duration.ofMillis(random.applyAsLong(ceiling));
    }
}
