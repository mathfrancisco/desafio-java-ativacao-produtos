package com.desafio.activation.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfig {

    static final String READ = "SCOPE_activation:read";
    static final String WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*").hasAuthority(READ)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(properties.issuerUri())) {
            decoder = JwtDecoders.fromIssuerLocation(properties.issuerUri());
            validators.add(JwtValidators.createDefaultWithIssuer(properties.issuerUri()));
        } else {
            var key = new SecretKeySpec(properties.devSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
            validators.add(JwtValidators.createDefault());
        }
        if (StringUtils.hasText(properties.audience())) {
            validators.add(audienceValidator(properties.audience()));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        var error = new OAuth2Error("invalid_token", "The required audience is missing", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
