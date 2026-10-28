package com.desafio.activation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE) String externalId,
        @NotBlank @Size(max = 64) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE) String customerId,
        @NotBlank @Size(max = 64) @Pattern(regexp = IDENTIFIER, message = IDENTIFIER_MESSAGE) String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9._:-]*$";
    static final String IDENTIFIER_MESSAGE = "must contain only letters, digits, '.', '_', ':' or '-'";
}
