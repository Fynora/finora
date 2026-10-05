package com.finora.notification.campaign;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one-custom-push-per-person-per-IST-day rule, enforced by the database.
 *
 * <p>{@link #claim} inserts {@code (user_id, day_ist)} with {@code ON CONFLICT DO NOTHING}; exactly
 * one caller per person per day gets {@code true}. Two campaigns running in the same minute (or two
 * servers) can therefore never both queue the same person, with no lock and no check-then-act race.
 * It must be called inside the same transaction that queues the notification, so the claim and the
 * outbox row commit or roll back together -- a claim without a queued row would silently cost a
 * person their push for the day.
 *
 * <p>Test sends never touch this table.
 */
@Component
public class DailyCapStore {

    private final JdbcTemplate jdbc;

    public DailyCapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this caller now holds the person's slot for {@code day}. */
    public boolean claim(UUID userId, LocalDate day, UUID campaignId) {
        return jdbc.update("""
                INSERT INTO custom_push_daily_cap (user_id, day_ist, campaign_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, day_ist) DO NOTHING
                """, userId, day, campaignId) == 1;
    }

    /** Which campaign holds the person's slot for {@code day}, if any. */
    public Optional<UUID> holder(UUID userId, LocalDate day) {
        List<UUID> rows = jdbc.queryForList(
                "SELECT campaign_id FROM custom_push_daily_cap WHERE user_id = ? AND day_ist = ?",
                UUID.class, userId, day);
        return rows.stream().findFirst();
    }

    /** Deletes rows older than {@code cutoff} (exclusive). Returns how many. */
    public int deleteBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM custom_push_daily_cap WHERE day_ist < ?", cutoff);
    }
}
