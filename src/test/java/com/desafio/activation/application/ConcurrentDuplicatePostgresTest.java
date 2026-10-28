package com.desafio.activation.application;

import com.desafio.activation.application.ActivationRequestService.CreationResult;
import com.desafio.activation.application.ActivationRequestService.NewActivationRequest;
import com.desafio.activation.application.exceptions.DuplicateExternalIdException;
import com.desafio.activation.domain.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real PostgreSQL to prove the unique constraint handles concurrent duplicates. */
@SpringBootTest(properties = "app.processing.worker-enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentDuplicatePostgresTest {

    private static final int THREADS = 16;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void concurrentIdenticalRequestsCreateOneRecord() throws Exception {
        var outcomes = runConcurrently(i -> new NewActivationRequest("EXT-RACE", "client-a", "CUST-1", "INTERNET-500"));

        assertThat(repository.count()).isEqualTo(1);
        var results = outcomes.stream().map(Outcome::result).toList();
        assertThat(results).doesNotContainNull();
        assertThat(results).filteredOn(CreationResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.request().getId()).containsOnly(results.getFirst().request().getId());
    }

    @Test
    void concurrentDivergentRequestsKeepOneAndRejectOthers() throws Exception {
        var outcomes = runConcurrently(i -> new NewActivationRequest("EXT-RACE-2", "client-a", "CUST-1", "PRODUCT-" + i));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.result() != null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() instanceof DuplicateExternalIdException)
                .hasSize(THREADS - 1);
    }

    private List<Outcome> runConcurrently(java.util.function.IntFunction<NewActivationRequest> commandFor)
            throws InterruptedException, ExecutionException {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                var command = commandFor.apply(i);
                Callable<Outcome> task = () -> {
                    start.await();
                    try {
                        return new Outcome(service.create(command), null);
                    } catch (RuntimeException e) {
                        return new Outcome(null, e);
                    }
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (var future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }

    private record Outcome(CreationResult result, RuntimeException error) {
    }
}
