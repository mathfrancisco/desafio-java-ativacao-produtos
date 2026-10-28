package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.domain.ActivationCommand;
import com.desafio.activation.domain.ActivationProvider;
import com.desafio.activation.domain.ProviderResult;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;

/**
 * HTTP adapter for POST /provider/v1/activate. The externalId is sent as the
 * Idempotency-Key so retries after a timeout do not activate twice, provided
 * the provider honours the key. A circuit breaker counts transient failures
 * and short-circuits calls while the provider is down; rejections (4xx) count
 * as successful calls because the provider itself is healthy. A bulkhead caps
 * concurrent calls per instance.
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public HttpActivationProvider(RestClient.Builder builder, ProviderProperties properties,
                                  CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public boolean isAvailable() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN
                && circuitBreaker.getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    @Override
    public ProviderResult activate(ActivationCommand command) {
        try {
            bulkhead.acquirePermission();
        } catch (BulkheadFullException e) {
            return new ProviderResult.TransientFailure("provider bulkhead full");
        }
        try {
            return guarded(command);
        } finally {
            bulkhead.onComplete();
        }
    }

    private ProviderResult guarded(ActivationCommand command) {
        if (!circuitBreaker.tryAcquirePermission()) {
            return new ProviderResult.TransientFailure("provider circuit breaker open");
        }
        long start = circuitBreaker.getCurrentTimestamp();
        ProviderResult result = call(command);
        long duration = circuitBreaker.getCurrentTimestamp() - start;
        if (result instanceof ProviderResult.TransientFailure failure) {
            circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(),
                    new ProviderUnavailableException(failure.reason()));
        } else {
            circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
        }
        return result;
    }

    private ProviderResult call(ActivationCommand command) {
        try {
            return restClient.post()
                    .uri(ACTIVATE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.externalId())
                    .header("X-Correlation-Id", command.requestId().toString())
                    .body(new ProviderActivationRequest(command.externalId(), command.customerId(),
                            command.productCode()))
                    .exchange((request, response) -> classify(response.getStatusCode()), true);
        } catch (RestClientException e) {
            // Timeouts and connection errors: outcome unknown, safe to retry with the same key.
            log.warn("Provider call for request {} failed: {}", command.requestId(), e.getMessage());
            return new ProviderResult.TransientFailure("provider unreachable or timed out");
        }
    }

    static ProviderResult classify(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Success();
        }
        if (status.is5xxServerError()
                || status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return new ProviderResult.TransientFailure("provider returned " + status.value());
        }
        return new ProviderResult.PermanentFailure("provider rejected request with " + status.value());
    }

    record ProviderActivationRequest(String externalId, String customerId, String productCode) {
    }

    private static final class ProviderUnavailableException extends RuntimeException {
        ProviderUnavailableException(String reason) {
            super(reason, null, false, false);
        }
    }
}
