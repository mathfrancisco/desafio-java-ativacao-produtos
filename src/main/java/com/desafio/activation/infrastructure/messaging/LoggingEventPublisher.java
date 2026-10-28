package com.desafio.activation.infrastructure.messaging;

import com.desafio.activation.domain.EventPublisher;
import com.desafio.activation.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in publisher while no broker is part of the stack: logs event metadata
 * (not the payload, which carries customer data). Replace with a Kafka or SNS
 * adapter that uses the event id as the message key.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("Published event {} type {} for request {}", event.getId(), event.getEventType(),
                event.getAggregateId());
    }
}
