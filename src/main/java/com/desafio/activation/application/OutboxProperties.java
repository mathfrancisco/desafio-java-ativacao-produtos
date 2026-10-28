package com.desafio.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize) {
}
