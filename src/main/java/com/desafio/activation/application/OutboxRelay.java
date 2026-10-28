package com.desafio.activation.application;

import com.desafio.activation.domain.EventPublisher;
import com.desafio.activation.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Publishes unpublished outbox events in creation order. The batch is locked
 * with SKIP LOCKED, so replicas do not publish the same events. An event is
 * marked published only after the broker accepted it; a crash before commit
 * republishes it (at least once). A failure stops the batch to keep the order.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final Duration retention;

    public OutboxRelay(OutboxEventRepository repository, EventPublisher publisher, TransactionTemplate transaction,
                       Clock clock, OutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.transaction = transaction;
        this.clock = clock;
        this.batchSize = properties.batchSize();
        this.retention = properties.retention();
    }

    /** Returns the number of events published. */
    public int publishPending() {
        return transaction.execute(status -> {
            int published = 0;
            for (var event : repository.lockUnpublished(PageRequest.of(0, batchSize))) {
                try {
                    publisher.publish(event);
                } catch (RuntimeException e) {
                    log.warn("Publishing event {} failed, will retry: {}", event.getId(), e.getMessage());
                    break;
                }
                event.markPublished(clock.instant());
                published++;
            }
            return published;
        });
    }

    /** Deletes events published longer ago than the retention period. Returns the number deleted. */
    public int purgePublished() {
        int deleted = transaction.execute(status ->
                repository.deletePublishedBefore(clock.instant().minus(retention)));
        if (deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
        return deleted;
    }
}
