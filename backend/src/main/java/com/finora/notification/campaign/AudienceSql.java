package com.finora.notification.campaign;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared SQL for audience resolvers: the base eligibility rule plus count and keyset paging over
 * whatever extra condition an audience adds.
 */
final class AudienceSql {

    /**
     * An ACTIVE end-user account (never a staff/admin-portal identity) with at least one live
     * device token that has not switched FINANCIAL push off. "Switched off" is an explicit
     * {@code enabled = false} row; no row means on, the same default
     * {@code DatabaseNotificationPreferenceResolver} applies. Status is ACTIVE only, which is
     * stricter than that resolver (it suppresses SUSPENDED, DEACTIVATED and PENDING_DELETION).
     */
    static final String BASE = """
            FROM users u
           WHERE u.status = 'ACTIVE'
             AND u.deleted_at IS NULL
             AND u.account_scope = 'USER'
             AND EXISTS (SELECT 1 FROM device_tokens d
                          WHERE d.user_id = u.id AND d.revoked_at IS NULL)
             AND NOT EXISTS (SELECT 1 FROM notification_preferences p
                              WHERE p.user_id = u.id AND p.category = 'FINANCIAL'
                                AND p.channel = 'PUSH' AND p.enabled = false)
            """;

    /** Smallest UUID, the "start from the beginning" cursor for keyset paging. */
    static final UUID FIRST = new UUID(0L, 0L);

    private AudienceSql() {
    }

    static long count(JdbcTemplate jdbc, String extraCondition) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + BASE + extraCondition, Long.class);
        return n == null ? 0 : n;
    }

    static List<UUID> page(JdbcTemplate jdbc, String extraCondition, UUID after, int limit) {
        return jdbc.queryForList(
                "SELECT u.id " + BASE + extraCondition + " AND u.id > ? ORDER BY u.id LIMIT ?",
                UUID.class, after, limit);
    }
}
