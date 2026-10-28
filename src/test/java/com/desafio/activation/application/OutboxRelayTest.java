package com.desafio.activation.application;

import com.desafio.activation.domain.EventPublisher;
import com.desafio.activation.domain.OutboxEvent;
import com.desafio.activation.domain.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;

@SpringBootTest
@ActiveProfiles("test")
class OutboxRelayTest {

    private static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    @MockitoBean
    private EventPublisher publisher;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    private OutboxEvent saveEvent(Instant createdAt) {
        return repository.save(OutboxEvent.create(UUID.randomUUID(), "activation.completed", "{}", createdAt));
    }

    @Test
    void publishesInCreationOrderAndMarksPublished() {
        var second = saveEvent(T0.plusSeconds(1));
        var first = saveEvent(T0);

        assertThat(relay.publishPending()).isEqualTo(2);

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(argThat(e -> e.getId().equals(first.getId())));
        order.verify(publisher).publish(argThat(e -> e.getId().equals(second.getId())));
        assertThat(repository.findAll()).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        assertThat(relay.publishPending()).isZero();
    }

    @Test
    void failureStopsBatchAndKeepsEventsForRetry() {
        saveEvent(T0);
        saveEvent(T0.plusSeconds(1));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

        assertThat(relay.publishPending()).isZero();

        verify(publisher, times(1)).publish(any());
        assertThat(repository.findAll()).allSatisfy(e -> assertThat(e.getPublishedAt()).isNull());
    }
}
