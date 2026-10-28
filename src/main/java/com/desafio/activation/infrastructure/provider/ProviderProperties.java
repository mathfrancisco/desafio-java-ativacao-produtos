package com.desafio.activation.infrastructure.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.provider")
public record ProviderProperties(
        String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Bulkhead bulkhead) {

    /** Opens after too many transient failures so the worker stops calling a provider that is down. */
    public record CircuitBreaker(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumNumberOfCalls,
            @DefaultValue("30s") Duration waitDurationInOpenState,
            @DefaultValue("3") int permittedCallsInHalfOpenState) {
    }

    /** Caps concurrent calls to the provider per instance; a call waits up to maxWait for a slot. */
    public record Bulkhead(
            @DefaultValue("4") int maxConcurrentCalls,
            @DefaultValue("1s") Duration maxWait) {
    }
}
