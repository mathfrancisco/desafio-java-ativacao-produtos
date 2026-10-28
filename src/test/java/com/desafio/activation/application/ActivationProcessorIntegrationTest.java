package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.domain.OutboxEvent;
import com.desafio.activation.domain.OutboxEventRepository;
import com.desafio.activation.support.MutableClock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.processing.max-attempts=3")
@ActiveProfiles("test")
class ActivationProcessorIntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";
    private static final Instant START = Instant.parse("2026-10-28T12:00:00Z");

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", provider::baseUrl);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        outbox.deleteAll();
        repository.deleteAll();
    }

    private List<String> eventTypes(UUID id) {
        return outbox.findByAggregateIdOrderByCreatedAt(id).stream().map(OutboxEvent::getEventType).toList();
    }

    private UUID pendingRequest() {
        var request = ActivationRequest.create("EXT-" + UUID.randomUUID(), "client-a", "CUST-1", "INTERNET-500",
                clock.instant());
        return repository.save(request).getId();
    }

    private ActivationRequest reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    @Test
    void successCompletesAndSendsIdempotencyKey() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        var id = pendingRequest();
        var externalId = reload(id).getExternalId();

        processor.process(id);

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(eventTypes(id)).containsExactly("activation.completed");
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(id).getFirst().getPayload())
                .contains("\"externalId\":\"%s\"".formatted(externalId), "\"status\":\"COMPLETED\"");
        provider.verify(postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(externalId))
                .withRequestBody(equalToJson("""
                        {"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(externalId))));
    }

    @Test
    void clientErrorFailsWithoutRetry() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(400).withBody("{\"secret\":\"x\"}")));
        var id = pendingRequest();

        processor.process(id);

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("provider rejected request with 400");
        assertThat(eventTypes(id)).containsExactly("activation.failed");
        provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void providerErrorSchedulesRetry() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
        var id = pendingRequest();

        processor.process(id);

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isAfterOrEqualTo(clock.instant());
        assertThat(eventTypes(id)).isEmpty();
    }

    @Test
    void timeoutSchedulesRetry() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        var id = pendingRequest();

        processor.process(id);

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.PENDING);
    }

    @Test
    void retryAfterProviderErrorThenSuccessCompletes() {
        provider.stubFor(post(ACTIVATE).inScenario("recover").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        provider.stubFor(post(ACTIVATE).inScenario("recover").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(201)));
        var id = pendingRequest();

        processor.process(id);
        clock.advance(Duration.ofMinutes(10));
        processor.process(id);

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void persistentProviderErrorFailsAfterMaxAttempts() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503)));
        var id = pendingRequest();

        for (int i = 0; i < 3; i++) {
            processor.process(id);
            clock.advance(Duration.ofMinutes(10));
        }

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getFailureReason()).startsWith("attempts exhausted");
        provider.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void retryIsNotProcessedBeforeItIsDue() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
        var id = pendingRequest();
        processor.process(id);
        var nextAttempt = reload(id).getNextAttemptAt();

        if (nextAttempt.isAfter(clock.instant())) {
            assertThat(repository.findDueIds(clock.instant(), PageRequest.of(0, 10))).doesNotContain(id);
        }
        assertThat(repository.findDueIds(nextAttempt, PageRequest.of(0, 10))).contains(id);
    }

    @Test
    void abandonedProcessingRequestIsReclaimedAfterLease() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        var id = pendingRequest();
        var request = reload(id);
        request.startProcessing(clock.instant(), Duration.ofSeconds(30));
        repository.saveAndFlush(request);

        processor.process(id);
        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.PROCESSING);

        clock.advance(Duration.ofSeconds(31));
        assertThat(repository.findDueIds(clock.instant(), PageRequest.of(0, 10))).contains(id);
        processor.process(id);

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(reload(id).getAttempts()).isEqualTo(2);
    }

    @Test
    void finishedRequestIsNotProcessedAgain() {
        provider.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        var id = pendingRequest();

        processor.process(id);
        processor.process(id);

        provider.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }
}
