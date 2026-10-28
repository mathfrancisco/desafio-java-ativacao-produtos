package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.domain.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Builds the outbox events emitted when a request reaches a final state. */
@Component
public class ActivationEvents {

    static final String COMPLETED = "activation.completed";
    static final String FAILED = "activation.failed";

    private final ObjectMapper objectMapper;

    public ActivationEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEvent finished(ActivationRequest request, Instant now) {
        String type = switch (request.getStatus()) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case PENDING, PROCESSING -> throw new IllegalStateException(
                    "Request %s is not finished".formatted(request.getId()));
        };
        var payload = new Payload(request.getId(), request.getExternalId(), request.getClientId(),
                request.getCustomerId(), request.getProductCode(), request.getStatus(), request.getAttempts(),
                request.getFailureReason(), now);
        return OutboxEvent.create(request.getId(), type, toJson(payload), now);
    }

    private String toJson(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event for request " + payload.requestId(), e);
        }
    }

    record Payload(UUID requestId, String externalId, String clientId, String customerId, String productCode,
                   ActivationStatus status, int attempts, String failureReason, Instant occurredAt) {
    }
}
