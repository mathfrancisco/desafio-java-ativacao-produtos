package com.desafio.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.processing")
public record ProcessingProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2s") Duration retryBaseDelay,
        @DefaultValue("5m") Duration retryMaxDelay,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("8") int batchSize,
        @DefaultValue("4") int concurrency) {
}
