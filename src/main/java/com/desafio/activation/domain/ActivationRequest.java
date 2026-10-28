package com.desafio.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Activation request and its state machine. Every status change goes through
 * the transition methods below; invalid transitions fail fast.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivationRequest() {
        // required by JPA
    }

    public static ActivationRequest create(String externalId, String clientId, String customerId,
                                           String productCode, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.clientId = Objects.requireNonNull(clientId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.status = ActivationStatus.PENDING;
        request.attempts = 0;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** True when a repeated submission carries the same content as this request. */
    public boolean isSameSubmission(String clientId, String customerId, String productCode) {
        return this.clientId.equals(clientId)
                && this.customerId.equals(customerId)
                && this.productCode.equals(productCode);
    }

    /** Pending and due, or processing with an expired lease (abandoned by a crashed worker). */
    public boolean isClaimable(Instant now) {
        return switch (status) {
            case PENDING -> !nextAttemptAt.isAfter(now);
            case PROCESSING -> lockedUntil == null || lockedUntil.isBefore(now);
            case COMPLETED, FAILED -> false;
        };
    }

    /** True while this request is still held by the worker that started the given attempt. */
    public boolean isProcessingAttempt(int attempt) {
        return status == ActivationStatus.PROCESSING && attempts == attempt;
    }

    public void startProcessing(Instant now, Duration lease) {
        if (!isClaimable(now)) {
            throw new IllegalStateException("Request %s cannot start processing from %s".formatted(id, status));
        }
        status = ActivationStatus.PROCESSING;
        attempts++;
        lockedUntil = now.plus(lease);
        updatedAt = now;
    }

    public void complete(Instant now) {
        requireProcessing("complete");
        status = ActivationStatus.COMPLETED;
        lockedUntil = null;
        failureReason = null;
        updatedAt = now;
    }

    public void scheduleRetry(Instant nextAttemptAt, Instant now) {
        requireProcessing("retry");
        status = ActivationStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        lockedUntil = null;
        updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        requireProcessing("fail");
        status = ActivationStatus.FAILED;
        failureReason = reason;
        lockedUntil = null;
        updatedAt = now;
    }

    private void requireProcessing(String transition) {
        if (status != ActivationStatus.PROCESSING) {
            throw new IllegalStateException("Request %s cannot %s from %s".formatted(id, transition, status));
        }
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
