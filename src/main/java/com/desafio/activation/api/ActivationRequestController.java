package com.desafio.activation.api;

import com.desafio.activation.api.dto.ActivationRequestResponse;
import com.desafio.activation.api.dto.CreateActivationRequest;
import com.desafio.activation.api.dto.PageResponse;
import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.ActivationRequestService.NewActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody CreateActivationRequest body) {
        var result = service.create(new NewActivationRequest(body.externalId(), AuthenticatedClient.idOf(jwt),
                body.customerId(), body.productCode()));
        var response = ActivationRequestResponse.from(result.request());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(id, AuthenticatedClient.idOf(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = service.search(AuthenticatedClient.idOf(jwt), status, customerId, pageable);
        return PageResponse.from(result, ActivationRequestResponse::from);
    }
}
