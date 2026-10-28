package com.desafio.activation.domain;

/** Port to the external activation provider. Implementations must not throw for HTTP or network failures. */
public interface ActivationProvider {

    ProviderResult activate(ActivationCommand command);

    /** False while calls are known to fail (circuit open); callers should wait instead of spending attempts. */
    default boolean isAvailable() {
        return true;
    }
}
