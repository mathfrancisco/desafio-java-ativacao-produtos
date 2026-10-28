package com.desafio.activation.domain;

import java.util.UUID;

public record ActivationCommand(UUID requestId, String externalId, String customerId, String productCode) {

    public static ActivationCommand from(ActivationRequest request) {
        return new ActivationCommand(request.getId(), request.getExternalId(),
                request.getCustomerId(), request.getProductCode());
    }
}
