package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.domain.ActivationCommand;
import com.desafio.activation.domain.ProviderResult;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
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

class HttpActivationProviderResilienceTest {

    private static final String ACTIVATE = HttpActivationProvider.ACTIVATE_PATH;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private CircuitBreaker circuitBreaker;
    private Bulkhead bulkhead;
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
                new ProviderProperties.CircuitBreaker(50, 2, 2, Duration.ofMinutes(1), 1),
                new ProviderProperties.Bulkhead(1, Duration.ZERO));
        bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO)
                .build());
        provider = new HttpActivationProvider(RestClient.builder(), properties, circuitBreaker, bulkhead);
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

    @Test
    void fullBulkheadRejectsWithoutCallingProvider() {
        wireMock.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        bulkhead.acquirePermission();
        try {
            assertThat(provider.activate(command()))
                    .isEqualTo(new ProviderResult.TransientFailure("provider bulkhead full"));
        } finally {
            bulkhead.onComplete();
        }

        wireMock.verify(0, postRequestedFor(urlEqualTo(ACTIVATE)));
        assertThat(provider.activate(command())).isInstanceOf(ProviderResult.Success.class);
    }
}
