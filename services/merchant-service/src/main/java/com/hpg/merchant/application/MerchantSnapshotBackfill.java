package com.hpg.merchant.application;

import com.hpg.merchant.infrastructure.persistence.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Publishes snapshots in bulk:
 * <ul>
 *   <li>at startup, for merchants that have never had one (created before snapshots existed);</li>
 *   <li>on demand (OPS), for every merchant, e.g. to rebuild a replica from scratch.</li>
 * </ul>
 * Republishing is always safe: each snapshot carries a higher version and the full state.
 */
@Service
public class MerchantSnapshotBackfill {

    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotBackfill.class);
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final MerchantRepository merchants;
    private final MerchantSnapshots snapshots;
    private final TransactionTemplate tx;

    public MerchantSnapshotBackfill(JdbcTemplate jdbc, MerchantRepository merchants, MerchantSnapshots snapshots,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.snapshots = snapshots;
        this.tx = new TransactionTemplate(txManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishMissing() {
        int published = 0;
        List<UUID> batch;
        do {
            batch = jdbc.queryForList("SELECT id FROM merchants WHERE snapshot_version = 0 ORDER BY id LIMIT ?",
                    UUID.class, BATCH);
            published += publish(batch);
        } while (batch.size() == BATCH);
        if (published > 0) {
            log.info("Published initial snapshots for {} merchants", published);
        }
    }

    /** @return number of merchants whose snapshot was published */
    @PreAuthorize("hasRole('OPS')")
    public int republishAll() {
        int published = 0;
        UUID after = new UUID(0, 0);
        List<UUID> batch;
        do {
            batch = jdbc.queryForList("SELECT id FROM merchants WHERE id > ? ORDER BY id LIMIT ?", UUID.class, after, BATCH);
            published += publish(batch);
            if (!batch.isEmpty()) {
                after = batch.getLast();
            }
        } while (batch.size() == BATCH);
        log.info("Republished snapshots for {} merchants", published);
        return published;
    }

    private int publish(List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Integer n = tx.execute(s -> {
            ids.forEach(id -> merchants.findById(id).ifPresent(snapshots::publish));
            return ids.size();
        });
        return n == null ? 0 : n;
    }
}
