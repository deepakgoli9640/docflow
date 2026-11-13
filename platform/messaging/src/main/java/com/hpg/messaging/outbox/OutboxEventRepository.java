package com.hpg.messaging.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);

    long countByPublishedAtIsNull();

    /**
     * The next events to publish, oldest first, locked for this relay.
     *
     * <p>{@code jakarta.persistence.lock.timeout = -2} is Hibernate's SKIP LOCKED: a second relay (during
     * a rolling deploy, or if the ShedLock lease ever overlapped) takes different rows instead of blocking
     * on these. Ordering by {@code id} — a database sequence — is what preserves per-aggregate event order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.id")
    List<OutboxEvent> lockBatchToPublish(Pageable pageable);

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Instant oldestUnpublishedCreatedAt();

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
