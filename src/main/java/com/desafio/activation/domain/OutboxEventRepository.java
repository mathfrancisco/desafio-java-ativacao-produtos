package com.desafio.activation.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt")
    List<OutboxEvent> findUnpublished(Pageable pageable);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(UUID aggregateId);
}
