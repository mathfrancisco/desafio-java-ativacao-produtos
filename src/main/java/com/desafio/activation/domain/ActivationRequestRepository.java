package com.desafio.activation.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

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

    /**
     * Same filter as {@link #findDueIds}, locking the rows with FOR UPDATE SKIP LOCKED so
     * concurrent workers take disjoint batches. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select r from ActivationRequest r
            where (r.status = com.desafio.activation.domain.ActivationStatus.PENDING and r.nextAttemptAt <= :now)
               or (r.status = com.desafio.activation.domain.ActivationStatus.PROCESSING and r.lockedUntil < :now)
            order by r.nextAttemptAt
            """)
    List<ActivationRequest> lockDue(@Param("now") Instant now, Pageable pageable);
}
