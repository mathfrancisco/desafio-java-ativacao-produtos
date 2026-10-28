package com.desafio.activation.application.exceptions;

public class DuplicateExternalIdException extends RuntimeException {

    public DuplicateExternalIdException(String externalId) {
        super("externalId " + externalId + " already used with different content");
    }
}
