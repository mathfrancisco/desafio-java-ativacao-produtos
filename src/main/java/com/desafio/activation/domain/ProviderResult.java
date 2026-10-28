package com.desafio.activation.domain;

/** Outcome of a provider call, already classified by the retry policy it implies. */
public sealed interface ProviderResult {

    record Success() implements ProviderResult {
    }

    /** The provider rejected the request; retrying cannot succeed. */
    record PermanentFailure(String reason) implements ProviderResult {
    }

    /** The outcome is a temporary error or unknown (timeout); retrying with the same idempotency key is safe. */
    record TransientFailure(String reason) implements ProviderResult {
    }
}
