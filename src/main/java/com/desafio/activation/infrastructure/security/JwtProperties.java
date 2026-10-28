package com.desafio.activation.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * issuerUri is used in every real environment (keys come from the issuer JWKS).
 * devSecret is a local-only HS256 fallback used when no issuer is configured.
 */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(String issuerUri, String audience, String devSecret) {
}
