package com.desafio.activation.api;

import org.springframework.security.oauth2.jwt.Jwt;

/** Identity of the calling system: the OAuth client (azp) when present, otherwise the subject. */
final class AuthenticatedClient {

    private AuthenticatedClient() {
    }

    static String idOf(Jwt jwt) {
        String authorizedParty = jwt.getClaimAsString("azp");
        return authorizedParty != null ? authorizedParty : jwt.getSubject();
    }
}
