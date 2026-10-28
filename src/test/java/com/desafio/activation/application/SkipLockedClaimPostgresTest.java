package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.ActivationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves on a real PostgreSQL that workers claiming at the same time get disjoint batches. */
@SpringBootTest(properties = {"app.processing.worker-enabled=false", "app.outbox.relay-enabled=false"})
@Testcontainers(disabledWithoutDocker = true)
class SkipLockedClaimPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void claimSkipsRowsLockedByAnotherWorker() {
        Instant past = Instant.now().minusSeconds(60);
        for (int i = 0; i < 4; i++) {
            repository.save(ActivationRequest.create("EXT-LOCK-" + i, "client-a", "CUST-1", "INTERNET-500",
                    past.plusSeconds(i)));
        }

        List<UUID> heldByOther = transaction.execute(status -> {
            var locked = repository.lockDue(Instant.now(), PageRequest.of(0, 2));
            // While this transaction holds two rows, another worker claims from a different connection.
            var claimed = CompletableFuture.supplyAsync(() -> processor.claimDue(10)).join();
            assertThat(claimed).extracting(ActivationRequest::getId)
                    .doesNotContainAnyElementsOf(locked.stream().map(ActivationRequest::getId).toList())
                    .hasSize(2);
            return locked.stream().map(ActivationRequest::getId).toList();
        });

        assertThat(repository.findAll())
                .filteredOn(request -> request.getStatus() == ActivationStatus.PROCESSING)
                .extracting(ActivationRequest::getId)
                .doesNotContainAnyElementsOf(heldByOther)
                .hasSize(2);
    }
}
