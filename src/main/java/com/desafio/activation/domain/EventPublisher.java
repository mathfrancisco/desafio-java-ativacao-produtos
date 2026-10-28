package com.desafio.activation.domain;

/** Port to the message broker. Throwing leaves the event unpublished so the relay retries it. */
public interface EventPublisher {

    void publish(OutboxEvent event);
}
