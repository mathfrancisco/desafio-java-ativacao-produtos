package com.desafio.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Event written in the same transaction as the state change it describes
 * (transactional outbox). A relay publishes it later; delivery is at least
 * once, so consumers deduplicate by {@link #getId()}.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // required by JPA
    }

    public static OutboxEvent create(UUID aggregateId, String eventType, String payload, Instant now) {
        var event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.aggregateId = Objects.requireNonNull(aggregateId);
        event.eventType = Objects.requireNonNull(eventType);
        event.payload = Objects.requireNonNull(payload);
        event.createdAt = now;
        return event;
    }

    public void markPublished(Instant now) {
        publishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
