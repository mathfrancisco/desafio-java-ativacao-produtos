package com.desafio.activation.infrastructure.provider;

import com.desafio.activation.domain.ActivationCommand;
import com.desafio.activation.domain.ActivationProvider;
import com.desafio.activation.domain.ProviderResult;
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
 * the provider honours the key.
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;

    public HttpActivationProvider(RestClient.Builder builder, ProviderProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public ProviderResult activate(ActivationCommand command) {
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
}
