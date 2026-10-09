package com.finora.notification.campaign;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The campaign-pushes-per-person-per-IST-day limit, enforced by the database.
 *
 * <p>A person's day is a set of numbered slots. {@link #claim} tries slots {@code 1..limit}, each
 * with {@code INSERT ... ON CONFLICT DO NOTHING}; the first insert that sticks wins. Two campaigns
 * running in the same minute (or two servers) can therefore never both take the same slot, with no
 * lock and no check-then-act race. A campaign takes at most one slot per person per day (a unique
 * constraint on user, day and campaign), so a resumed or repeated run of the same campaign finds
 * every insert refused and leaves the person's other slots alone.
 *
 * <p>It must be called inside the same transaction that queues the notification, so the claim and
 * the outbox row commit or roll back together -- a claim without a queued row would silently cost a
 * person a push for the day.
 *
 * <p>The limit is a parameter, not a stored count: lowering it never takes anything back from
 * someone who already received their pushes, it only stops further claims, and raising it makes the
 * extra slots available at once.
 *
 * <p>Test sends never touch this table.
 */
@Component
public class DailyCapStore {

    private final JdbcTemplate jdbc;

    public DailyCapStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One push a day, the default limit. */
    public boolean claim(UUID userId, LocalDate day, UUID campaignId) {
        return claim(userId, day, campaignId, 1);
    }

    /**
     * @return true if this caller now holds one of the person's {@code limit} slots for {@code day};
     *     false if all are taken, or this campaign already holds one (see {@link #heldBy})
     */
    public boolean claim(UUID userId, LocalDate day, UUID campaignId, int limit) {
        for (int slot = 1; slot <= limit; slot++) {
            // No conflict target: refused by the slot's primary key (taken) or by the unique
            // (user, day, campaign) constraint (this campaign already holds a slot).
            //
            // The NOT EXISTS guard is what makes "slots 1..limit" mean "at most limit pushes". A
            // cancel frees a low slot while the person's other push keeps a higher one, and the
            // limit can be lowered after that: with a push in slot 2, a limit of 1 and slot 1 free,
            // taking slot 1 would give the person two pushes against a limit of one. So a person
            // holding any slot above the limit has, by definition, used up what the limit allows.
            // Every row is then inside 1..limit, so a free slot exists exactly when the person is
            // under the limit. (Lowering the limit past a person's only, high slot therefore leaves
            // them with fewer than the limit until the day ends: the safe side of the error.)
            int inserted = jdbc.update("""
                    INSERT INTO custom_push_daily_cap (user_id, day_ist, slot, campaign_id)
                    SELECT ?::uuid, ?::date, ?::smallint, ?::uuid
                     WHERE NOT EXISTS (SELECT 1 FROM custom_push_daily_cap
                                        WHERE user_id = ?::uuid AND day_ist = ?::date AND slot > ?::smallint)
                    ON CONFLICT DO NOTHING
                    """, userId, day, slot, campaignId, userId, day, limit);
            if (inserted == 1) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code campaignId} already holds one of the person's slots for {@code day}. */
    public boolean heldBy(UUID userId, LocalDate day, UUID campaignId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM custom_push_daily_cap WHERE user_id = ? AND day_ist = ? AND campaign_id = ?",
                Integer.class, userId, day, campaignId);
        return count != null && count > 0;
    }

    /** Which campaign holds the person's first slot for {@code day}, if any. */
    public Optional<UUID> holder(UUID userId, LocalDate day) {
        List<UUID> rows = jdbc.queryForList(
                "SELECT campaign_id FROM custom_push_daily_cap WHERE user_id = ? AND day_ist = ? ORDER BY slot LIMIT 1",
                UUID.class, userId, day);
        return rows.stream().findFirst();
    }

    /** How many slots the person has used on {@code day}. */
    public int used(UUID userId, LocalDate day) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM custom_push_daily_cap WHERE user_id = ? AND day_ist = ?",
                Integer.class, userId, day);
        return count == null ? 0 : count;
    }

    /** Deletes rows older than {@code cutoff} (exclusive). Returns how many. */
    public int deleteBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM custom_push_daily_cap WHERE day_ist < ?", cutoff);
    }
}
