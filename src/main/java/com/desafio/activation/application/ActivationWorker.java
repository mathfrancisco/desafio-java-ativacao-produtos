package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationProvider;
import com.desafio.activation.domain.ActivationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claims a batch of due requests (pending or with an expired lease) and processes
 * them with bounded concurrency. Skips the poll while the provider circuit is open
 * so requests keep their remaining attempts. The batch must finish within the
 * lease: ceil(batchSize / concurrency) x (connect + read timeout) < lease.
 */
@Component
@ConditionalOnProperty(name = "app.processing.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {

    private static final Logger log = LoggerFactory.getLogger(ActivationWorker.class);

    private final ActivationProcessor processor;
    private final ActivationProvider provider;
    private final ExecutorService executor;
    private final int batchSize;

    public ActivationWorker(ActivationProcessor processor, ActivationProvider provider,
                            ProcessingProperties properties) {
        this.processor = processor;
        this.provider = provider;
        this.executor = Executors.newFixedThreadPool(properties.concurrency(),
                Thread.ofPlatform().name("activation-", 0).factory());
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-interval:1s}")
    public void run() throws InterruptedException {
        if (!provider.isAvailable()) {
            log.debug("Provider unavailable (circuit open), skipping poll");
            return;
        }
        var tasks = processor.claimDue(batchSize).stream().map(this::task).toList();
        executor.invokeAll(tasks);
    }

    private Callable<Void> task(ActivationRequest request) {
        return () -> {
            MDC.put("requestId", request.getId().toString());
            try {
                processor.execute(request);
            } catch (RuntimeException e) {
                log.error("Unexpected error processing request {}", request.getId(), e);
            } finally {
                MDC.remove("requestId");
            }
            return null;
        };
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
