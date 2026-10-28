package com.desafio.activation.domain;

public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
