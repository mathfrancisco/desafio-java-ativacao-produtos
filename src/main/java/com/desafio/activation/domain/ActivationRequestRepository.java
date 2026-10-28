package com.desafio.activation.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Query("""
            select r.id from ActivationRequest r
            where (r.status = com.desafio.activation.domain.ActivationStatus.PENDING and r.nextAttemptAt <= :now)
               or (r.status = com.desafio.activation.domain.ActivationStatus.PROCESSING and r.lockedUntil < :now)
            order by r.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);
}
