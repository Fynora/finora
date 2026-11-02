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
     * device token. Status is ACTIVE only, which is stricter than
     * {@code DatabaseNotificationPreferenceResolver} (it suppresses SUSPENDED, DEACTIVATED and
     * PENDING_DELETION).
     */
    private static final String ELIGIBLE = """
            FROM users u
           WHERE u.status = 'ACTIVE'
             AND u.deleted_at IS NULL
             AND u.account_scope = 'USER'
             AND EXISTS (SELECT 1 FROM device_tokens d
                          WHERE d.user_id = u.id AND d.revoked_at IS NULL)
            """;

    /** OPERATIONAL: FINANCIAL push not switched off. "Switched off" is an explicit
     *  {@code enabled = false} row; no row means on, the resolver's default for FINANCIAL. */
    private static final String FINANCIAL_PUSH_NOT_OFF = """
             AND NOT EXISTS (SELECT 1 FROM notification_preferences p
                              WHERE p.user_id = u.id AND p.category = 'FINANCIAL'
                                AND p.channel = 'PUSH' AND p.enabled = false)
            """;

    /** PROMOTIONAL: MARKETING push turned on. Only an explicit {@code enabled = true} row counts;
     *  no row means off, the resolver's default for MARKETING. */
    private static final String MARKETING_PUSH_ON = """
             AND EXISTS (SELECT 1 FROM notification_preferences p
                          WHERE p.user_id = u.id AND p.category = 'MARKETING'
                            AND p.channel = 'PUSH' AND p.enabled = true)
            """;

    /** The base rule for a campaign of this purpose: eligible, and allowed to receive it. */
    static String base(CampaignPurpose purpose) {
        return ELIGIBLE + (purpose == CampaignPurpose.PROMOTIONAL ? MARKETING_PUSH_ON : FINANCIAL_PUSH_NOT_OFF);
    }

    /** The OPERATIONAL rule, the only one before V305. */
    static final String BASE = base(CampaignPurpose.OPERATIONAL);

    /** Smallest UUID, the "start from the beginning" cursor for keyset paging. */
    static final UUID FIRST = new UUID(0L, 0L);

    private AudienceSql() {
    }

    static long count(JdbcTemplate jdbc, CampaignPurpose purpose, String extraCondition) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) " + base(purpose) + extraCondition, Long.class);
        return n == null ? 0 : n;
    }

    static List<UUID> page(JdbcTemplate jdbc, CampaignPurpose purpose, String extraCondition, UUID after, int limit) {
        return jdbc.queryForList(
                "SELECT u.id " + base(purpose) + extraCondition + " AND u.id > ? ORDER BY u.id LIMIT ?",
                UUID.class, after, limit);
    }
}
