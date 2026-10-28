package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.domain.ActivationCommand;
import com.desafio.activation.domain.ProviderResult;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class HttpActivationProviderCircuitBreakerTest {

    private static final String ACTIVATE = HttpActivationProvider.ACTIVATE_PATH;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private CircuitBreaker circuitBreaker;
    private HttpActivationProvider provider;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        var properties = new ProviderProperties(wireMock.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ProviderProperties.CircuitBreaker(50, 2, 2, Duration.ofMinutes(1), 1));
        provider = new HttpActivationProvider(RestClient.builder(), properties, circuitBreaker);
    }

    private static ActivationCommand command() {
        return new ActivationCommand(UUID.randomUUID(), "EXT-" + UUID.randomUUID(), "CUST-1", "INTERNET-500");
    }

    @Test
    void transientFailuresOpenCircuitAndStopCalls() {
        wireMock.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503)));

        provider.activate(command());
        provider.activate(command());

        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.activate(command()))
                .isEqualTo(new ProviderResult.TransientFailure("provider circuit breaker open"));
        wireMock.verify(2, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void rejectionsDoNotOpenCircuit() {
        wireMock.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(422)));

        for (int i = 0; i < 4; i++) {
            assertThat(provider.activate(command())).isInstanceOf(ProviderResult.PermanentFailure.class);
        }

        assertThat(provider.isAvailable()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
