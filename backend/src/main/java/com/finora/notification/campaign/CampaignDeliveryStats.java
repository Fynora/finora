package com.finora.notification.campaign;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Live delivery outcome of one campaign day, read from the outbox rather than stored: a failed count
 * copied onto the run row would be wrong the moment the dispatcher retried one.
 *
 * <p>Matches by key prefix ({@code PUSHCAMPAIGN_{campaignId}_{yyyyMMdd}_}); test sends use a
 * different prefix and are never counted. The partial index
 * {@code notification_key text_pattern_ops WHERE type = 'CUSTOM_PUSH'} (V261) serves the prefix
 * match. Underscores in the prefix are LIKE wildcards, so they are escaped.
 */
@Component
public class CampaignDeliveryStats {

    /** Outbox rows grouped into what an admin cares about. */
    public record Delivery(long sent, long failed, long pending, long cancelled, long skipped) {
        static final Delivery NONE = new Delivery(0, 0, 0, 0, 0);
    }

    private final JdbcTemplate jdbc;

    public CampaignDeliveryStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Delivery forRunDay(UUID campaignId, LocalDate runDate) {
        String pattern = CampaignEnqueuer.keyPrefix(campaignId, runDate).replace("_", "\\_") + "%";
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS n FROM notifications
                 WHERE type = 'CUSTOM_PUSH' AND notification_key LIKE ? ESCAPE '\\'
                 GROUP BY status
                """, rs -> {
            byStatus.put(rs.getString("status"), rs.getLong("n"));
        }, pattern);
        if (byStatus.isEmpty()) {
            return Delivery.NONE;
        }
        long pending = 0;
        for (String status : new String[] {"CREATED", "QUEUED", "PROCESSING", "RETRYING"}) {
            pending += byStatus.getOrDefault(status, 0L);
        }
        return new Delivery(byStatus.getOrDefault("SENT", 0L),
                byStatus.getOrDefault("DEAD_LETTER", 0L), pending,
                byStatus.getOrDefault("CANCELLED", 0L), byStatus.getOrDefault("SKIPPED", 0L));
    }
}
