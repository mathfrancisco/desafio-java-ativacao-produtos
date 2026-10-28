package com.desafio.activation.application;

import com.desafio.activation.application.exceptions.DuplicateExternalIdException;
import com.desafio.activation.application.exceptions.RequestNotFoundException;
import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationRequestRepository;
import com.desafio.activation.domain.ActivationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Creates a request or returns the existing one for an identical retry.
     * The unique constraint on external_id is the real guard; the lookup first
     * only avoids an exception for the common sequential retry.
     * Not transactional on purpose: saveAndFlush runs in its own transaction so
     * a constraint violation can be handled here.
     */
    public CreationResult create(NewActivationRequest command) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return replayOf(existing.get(), command);
        }
        var request = ActivationRequest.create(command.externalId(), command.clientId(),
                command.customerId(), command.productCode(), clock.instant());
        try {
            return new CreationResult(repository.saveAndFlush(request), true);
        } catch (DataIntegrityViolationException e) {
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            return replayOf(winner, command);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new RequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(String clientId, ActivationStatus status, String customerId,
                                          Pageable pageable) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        return repository.findAll(spec, pageable);
    }

    private CreationResult replayOf(ActivationRequest existing, NewActivationRequest command) {
        if (!existing.isSameSubmission(command.clientId(), command.customerId(), command.productCode())) {
            throw new DuplicateExternalIdException(command.externalId());
        }
        return new CreationResult(existing, false);
    }

    public record NewActivationRequest(String externalId, String clientId, String customerId, String productCode) {
    }

    public record CreationResult(ActivationRequest request, boolean created) {
    }
}
