package com.finora.notification.campaign;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The emergency brake: withdraws a campaign's pushes that have been queued but not yet handed to
 * the dispatcher, and gives those people their daily slot back.
 *
 * <p>Stopping a campaign only prevents future runs. Without this, a wrong message sent to thousands
 * of people would keep delivering for hours (the dispatcher clears about 6,000 an hour) with no way
 * to halt it.
 *
 * <p>One statement does both halves, so a person is never left with a cancelled push and a spent
 * slot: the rows go QUEUED/CREATED/RETRYING to CANCELLED, and the matching {@code custom_push_daily_cap}
 * rows (same campaign, the IST day parsed from the row's own key) are deleted -- otherwise a
 * corrected message sent the same day would skip exactly the people who never received the wrong
 * one. Rows already PROCESSING (claimed by the dispatcher, at most one batch) are not touched and
 * will be sent. Test sends use another key prefix and are never affected.
 *
 * <p>A cancelled row's {@code notification_key} gets a unique suffix. The key is the dedupe guard
 * (one row per person per campaign per day); leaving it on a withdrawn row would make the same
 * campaign, after the admin fixes its text and sends again the same day, silently skip exactly the
 * people who never received anything. The suffix keeps the row (and its prefix, so run counts still
 * find it) while freeing the key.
 *
 * <p>Idempotent: a second call finds nothing to cancel.
 */
@Component
public class CampaignCancellation {

    /** What one call did. */
    public record Result(long cancelled, long released) {
    }

    private final JdbcTemplate jdbc;

    public CampaignCancellation(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result cancelPending(UUID campaignId) {
        String pattern = (CampaignEnqueuer.KEY_PREFIX + campaignId + "_").replace("_", "\\_") + "%";
        return jdbc.queryForObject("""
                WITH cancelled AS (
                    UPDATE notifications
                       SET status = 'CANCELLED', last_error = 'Cancelled by an admin before it was sent',
                           notification_key = notification_key || '~cancelled~' || gen_random_uuid()
                     WHERE type = 'CUSTOM_PUSH'
                       AND notification_key LIKE ? ESCAPE '\\'
                       AND status IN ('CREATED', 'QUEUED', 'RETRYING')
                 RETURNING user_id, notification_key),
                released AS (
                    DELETE FROM custom_push_daily_cap c
                     USING cancelled x
                     WHERE c.user_id = x.user_id
                       AND c.campaign_id = ?
                       AND c.day_ist = to_date(
                               substring(x.notification_key FROM '^PUSHCAMPAIGN_[0-9a-f-]{36}_([0-9]{8})_'),
                               'YYYYMMDD')
                 RETURNING 1)
                SELECT (SELECT count(*) FROM cancelled) AS cancelled,
                       (SELECT count(*) FROM released) AS released
                """, (rs, row) -> new Result(rs.getLong("cancelled"), rs.getLong("released")),
                pattern, campaignId);
    }
}
