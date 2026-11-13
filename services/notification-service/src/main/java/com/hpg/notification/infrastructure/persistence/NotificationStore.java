package com.hpg.notification.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Plain-SQL parts of notification handling: claiming due work and the attempt log. */
@Component
public class NotificationStore {

    private final JdbcTemplate jdbc;

    public NotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Attempt(int attemptNo, String outcome, Integer httpStatus, String error, long durationMs,
            Instant attemptedAt) {
    }

    /**
     * Claims due notifications by pushing their next attempt into the future (a lease). {@code SKIP LOCKED}
     * lets several dispatchers (replicas) claim disjoint sets without waiting on each other; if a dispatcher
     * dies mid-delivery, the lease expires and another picks the notification up.
     */
    @Transactional
    public List<UUID> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.queryForList("""
                        UPDATE notifications SET next_attempt_at = ?
                        WHERE id IN (SELECT id FROM notifications
                                     WHERE status = 'PENDING' AND next_attempt_at <= ?
                                     ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING id""",
                UUID.class, Timestamp.from(leaseUntil), Timestamp.from(now), limit);
    }

    public void recordAttempt(UUID notificationId, int attemptNo, String outcome, Integer httpStatus, String error,
            long durationMs, Instant at) {
        jdbc.update("""
                        INSERT INTO notification_attempts (notification_id, attempt_no, outcome, http_status, error,
                                                           duration_ms, attempted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                notificationId, attemptNo, outcome, httpStatus,
                error == null || error.length() <= 500 ? error : error.substring(0, 500), durationMs, Timestamp.from(at));
    }

    public List<Attempt> attemptsOf(UUID notificationId) {
        return jdbc.query("""
                        SELECT attempt_no, outcome, http_status, error, duration_ms, attempted_at
                        FROM notification_attempts WHERE notification_id = ? ORDER BY id""",
                (rs, i) -> new Attempt(rs.getInt(1), rs.getString(2), (Integer) rs.getObject(3), rs.getString(4),
                        rs.getLong(5), rs.getTimestamp(6).toInstant()),
                notificationId);
    }
}
