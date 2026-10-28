package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Polls due requests (pending or with an expired lease) and hands each one to the processor. */
@Component
@ConditionalOnProperty(name = "app.processing.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivationWorker.class);

    private final ActivationRequestRepository repository;
    private final ActivationProcessor processor;
    private final Clock clock;
    private final int batchSize;

    public ActivationWorker(ActivationRequestRepository repository, ActivationProcessor processor, Clock clock,
                            ProcessingProperties properties) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-interval:1s}")
    public void run() {
        for (var id : repository.findDueIds(clock.instant(), PageRequest.of(0, batchSize))) {
            MDC.put("requestId", id.toString());
            try {
                processor.process(id);
            } catch (RuntimeException e) {
                log.error("Unexpected error processing request {}", id, e);
            } finally {
                MDC.remove("requestId");
            }
        }
    }
}
