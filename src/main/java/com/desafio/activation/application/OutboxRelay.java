package com.desafio.activation.application;

import com.desafio.activation.domain.EventPublisher;
import com.desafio.activation.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Publishes unpublished outbox events in creation order. Each event is marked
 * published only after the broker accepted it; a crash in between republishes
 * it (at least once). A failure stops the batch to keep the order.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository repository, EventPublisher publisher, TransactionTemplate transaction,
                       Clock clock, OutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    /** Returns the number of events published. */
    public int publishPending() {
        int published = 0;
        for (var event : repository.findUnpublished(PageRequest.of(0, batchSize))) {
            try {
                publisher.publish(event);
            } catch (RuntimeException e) {
                log.warn("Publishing event {} failed, will retry: {}", event.getId(), e.getMessage());
                break;
            }
            event.markPublished(clock.instant());
            transaction.executeWithoutResult(status -> repository.save(event));
            published++;
        }
        return published;
    }
}
