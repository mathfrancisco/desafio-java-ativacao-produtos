package com.desafio.activation.domain;

/** Port to the external activation provider. Implementations must not throw for HTTP or network failures. */
public interface ActivationProvider {

    ProviderResult activate(ActivationCommand command);
}
