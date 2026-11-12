package com.hpg.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);

    long countByPublishedAtIsNull();
}
