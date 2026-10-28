package com.desafio.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1s}")
    public void run() {
        relay.publishPending();
    }

    @Scheduled(fixedDelayString = "${app.outbox.purge-interval:1h}", initialDelayString = "${app.outbox.purge-interval:1h}")
    public void purge() {
        relay.purgePublished();
    }
}
