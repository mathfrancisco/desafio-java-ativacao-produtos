package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationCommand;
import com.desafio.activation.domain.ActivationProvider;
import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.domain.OutboxEventRepository;
import com.desafio.activation.domain.ProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Processes requests: claim them (short transaction), call the provider with
 * no transaction open, then record the outcome (short transaction). The attempt
 * number taken at claim time fences out workers whose lease already expired.
 * Final states also write an outbox event in the same transaction.
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final OutboxEventRepository outbox;
    private final ActivationEvents events;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration lease;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationProvider provider,
                               OutboxEventRepository outbox, ActivationEvents events, RetryPolicy retryPolicy,
                               TransactionTemplate transaction, Clock clock, ProcessingProperties properties) {
        this.repository = repository;
        this.provider = provider;
        this.outbox = outbox;
        this.events = events;
        this.retryPolicy = retryPolicy;
        this.transaction = transaction;
        this.clock = clock;
        this.lease = properties.lease();
    }

    public void process(UUID id) {
        claim(id).ifPresent(this::execute);
    }

    /**
     * Claims up to {@code limit} due requests in one transaction. Rows locked by
     * another worker are skipped, so concurrent workers never claim the same request.
     */
    public List<ActivationRequest> claimDue(int limit) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            var claimed = repository.lockDue(now, PageRequest.of(0, limit));
            claimed.forEach(request -> request.startProcessing(now, lease));
            return repository.saveAllAndFlush(claimed);
        });
    }

    /** Calls the provider for a request already claimed by this worker and records the outcome. */
    public void execute(ActivationRequest request) {
        UUID id = request.getId();
        int attempt = request.getAttempts();
        ProviderResult result = attempt > retryPolicy.maxAttempts()
                ? new ProviderResult.PermanentFailure("maximum attempts exceeded")
                : provider.activate(ActivationCommand.from(request));
        record(id, attempt, result);
    }

    private Optional<ActivationRequest> claim(UUID id) {
        try {
            return transaction.execute(status -> {
                Instant now = clock.instant();
                return repository.findById(id)
                        .filter(request -> request.isClaimable(now))
                        .map(request -> {
                            request.startProcessing(now, lease);
                            return repository.saveAndFlush(request);
                        });
            });
        } catch (OptimisticLockingFailureException e) {
            log.debug("Request {} claimed by another worker", id);
            return Optional.empty();
        }
    }

    private void record(UUID id, int attempt, ProviderResult result) {
        try {
            transaction.executeWithoutResult(status -> repository.findById(id)
                    .filter(request -> request.isProcessingAttempt(attempt))
                    .ifPresentOrElse(
                            request -> apply(request, attempt, result),
                            () -> log.warn("Discarding stale result for request {} attempt {}", id, attempt)));
        } catch (OptimisticLockingFailureException e) {
            log.warn("Discarding stale result for request {} attempt {}", id, attempt);
        }
    }

    private void apply(ActivationRequest request, int attempt, ProviderResult result) {
        Instant now = clock.instant();
        switch (result) {
            case ProviderResult.Success success -> {
                request.complete(now);
                log.info("Request {} completed on attempt {}", request.getId(), attempt);
            }
            case ProviderResult.PermanentFailure failure -> {
                request.fail(failure.reason(), now);
                log.warn("Request {} failed permanently: {}", request.getId(), failure.reason());
            }
            case ProviderResult.TransientFailure failure when retryPolicy.canRetry(attempt) -> {
                request.scheduleRetry(now.plus(retryPolicy.delayAfter(attempt)), now);
                log.info("Request {} attempt {} failed temporarily ({}), retry scheduled",
                        request.getId(), attempt, failure.reason());
            }
            case ProviderResult.TransientFailure failure -> {
                request.fail("attempts exhausted: " + failure.reason(), now);
                log.warn("Request {} failed after {} attempts: {}", request.getId(), attempt, failure.reason());
            }
        }
        repository.saveAndFlush(request);
        if (request.getStatus() == ActivationStatus.COMPLETED || request.getStatus() == ActivationStatus.FAILED) {
            outbox.save(events.finished(request, now));
        }
    }
}
