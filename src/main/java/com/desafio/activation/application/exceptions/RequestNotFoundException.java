package com.desafio.activation.application.exceptions;

import java.util.UUID;

public class RequestNotFoundException extends RuntimeException {

    public RequestNotFoundException(UUID id) {
        super("Activation request " + id + " not found");
    }
}
