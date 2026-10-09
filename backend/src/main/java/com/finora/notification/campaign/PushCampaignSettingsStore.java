package com.finora.notification.campaign;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one row of campaign settings an admin controls from the Push Campaigns screen: how many
 * campaign pushes one person may get in an IST day, across all campaigns.
 *
 * <p>Read fresh on every use (one primary-key lookup per queued page, not per person), never cached:
 * an admin who lowers the limit because people are complaining must see it bite on the very next
 * page, not after some cache expires. The default of 1 is what V261 shipped with.
 */
@Component
public class PushCampaignSettingsStore {

    /** Bounds of the limit; the table's CHECK constraint says the same. */
    public static final int MIN_DAILY_LIMIT = 1;
    public static final int MAX_DAILY_LIMIT = 10;

    /** The current settings. */
    public record Settings(int dailyLimitPerPerson, Instant updatedAt, UUID updatedBy) {
    }

    private final JdbcTemplate jdbc;

    public PushCampaignSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings get() {
        return jdbc.queryForObject(
                "SELECT daily_limit_per_person, updated_at, updated_by FROM push_campaign_settings WHERE id = 1",
                (rs, row) -> new Settings(rs.getInt("daily_limit_per_person"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_by", UUID.class)));
    }

    /** Just the number, for the hot path. */
    public int dailyLimitPerPerson() {
        Integer limit = jdbc.queryForObject(
                "SELECT daily_limit_per_person FROM push_campaign_settings WHERE id = 1", Integer.class);
        return limit == null ? MIN_DAILY_LIMIT : limit;
    }

    /** @return the limit as it was before this change */
    public int setDailyLimitPerPerson(int limit, UUID updatedBy, Instant now) {
        int previous = dailyLimitPerPerson();
        jdbc.update("UPDATE push_campaign_settings SET daily_limit_per_person = ?, updated_at = ?, "
                + "updated_by = ? WHERE id = 1", limit, OffsetDateTime.ofInstant(now, ZoneOffset.UTC), updatedBy);
        return previous;
    }
}
