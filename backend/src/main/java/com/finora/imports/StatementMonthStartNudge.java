package com.finora.imports;

import com.finora.entity.User;
import com.finora.notification.domain.NotificationType;
import com.finora.service.StatementStatusNotifier;
import com.finora.util.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Gate 2b spec §6: a push at the start of the month when a new statement has probably arrived.
 *
 * <h2>Two cases</h2>
 * <ul>
 *   <li><b>No data yet</b> -- no statement ever imported, deleted ones included
 *       ({@code ActivationFacts.hasEverImported}'s rule): on the 3rd of each month from 10:00 India
 *       time, to users who already had an account then; a run missed on the 3rd catches up until the
 *       9th. "Your April statement is out ..." on 3 May.</li>
 *   <li><b>Has data</b> -- some live account whose latest live statement's period ended more than 33
 *       days ago (today minus the period end is 34 days or more), with nothing newer for it and no
 *       import into it in the last 3 days: any day of the month, from 10:00 India time.</li>
 * </ul>
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>At most one nudge per user per calendar month, across both cases and every account: the two
 *       notification types share one outbox key per user per month, so a rerun -- a restart, or a
 *       second instance -- inserts nothing ({@link StatementStatusNotifier#notifyMonthStart}).</li>
 *   <li>The no-data case stops after two months in a row with no answer: a user who was sent it in
 *       each of the previous two months, and has opened the import screen on no day since the first
 *       of those two, is skipped. Opening the import screen starts it again.</li>
 *   <li>Only USER-scope, ACTIVE accounts with a live device token: the nudge is a push, and a user
 *       with no phone would only collect dead-lettered rows.</li>
 *   <li>A user who switched REMINDERS push off is never a candidate (and the outbox would refuse it
 *       anyway).</li>
 *   <li>Never a bank, an account or an amount in the text: it shows on lock screens.</li>
 * </ul>
 *
 * "Sent" in the stop rule means queued in the outbox: a queued push the device never showed still
 * counts as asked.
 */
@Component
public class StatementMonthStartNudge {

    private static final Logger log = LoggerFactory.getLogger(StatementMonthStartNudge.class);

    /** The no-data nudge is due on this day of the month (spec §6: the 3rd). */
    static final int FIRST_DAY_OF_MONTH = 3;
    /** The last day a missed no-data nudge is still sent (a deploy or an outage on the 3rd). After it
     *  the month is skipped: "your April statement is out" reads oddly in late May. */
    static final int LAST_CATCH_UP_DAY = 9;
    /** An overdue account whose latest statement was imported within this many days is left alone. */
    static final int RECENT_IMPORT_GRACE_DAYS = 3;
    /** Nothing is sent before this India time, on any day. */
    static final LocalTime SEND_FROM = LocalTime.of(10, 0);
    /** An account is overdue when its latest statement ended more than this many days ago. */
    static final int STALE_AFTER_DAYS = 33;
    /** Users read per case per run. A backlog drains over the next runs: a notified user drops out. */
    static final int BATCH_SIZE = 1000;

    @Value("${app.reminders.month-start.enabled:true}")
    private boolean enabled;

    private final JdbcTemplate jdbc;
    private final StatementStatusNotifier notifier;

    public StatementMonthStartNudge(JdbcTemplate jdbc, StatementStatusNotifier notifier) {
        this.jdbc = jdbc;
        this.notifier = notifier;
    }

    /** Hourly by default: the 10:00 start is reached within an hour, and nothing depends on speed.
     *  {@code fixedDelay}, as the other sweeps use, so a slow run cannot overlap the next. */
    @Scheduled(fixedDelayString = "${app.reminders.month-start.interval-ms:3600000}",
            initialDelayString = "${app.reminders.month-start.initial-delay-ms:300000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            int users = runDue(Instant.now());
            if (users > 0) log.info("Sent month-start statement nudges to {} user(s).", users);
        } catch (RuntimeException e) {
            log.warn("Month-start nudge run failed; the next run will retry.", e);
        }
    }

    /** One run as of {@code now}. Returns how many users were nudged. Does not consult the enabled
     *  flag, so tests and a dry run can drive it directly. */
    public int runDue(Instant now) {
        ZonedDateTime local = now.atZone(UserZone.DEFAULT);
        if (local.toLocalTime().isBefore(SEND_FROM)) return 0;
        LocalDate today = local.toLocalDate();
        YearMonth month = YearMonth.from(today);

        int nudged = 0;
        for (UUID userId : hasDataDue(now, today, month)) {
            if (send(userId, month, true)) nudged++;
        }
        int day = today.getDayOfMonth();
        if (day >= FIRST_DAY_OF_MONTH && day <= LAST_CATCH_UP_DAY) {
            for (UUID userId : noDataDue(now, month)) {
                if (send(userId, month, false)) nudged++;
            }
        }
        return nudged;
    }

    private boolean send(UUID userId, YearMonth month, boolean hasData) {
        try {
            return notifier.notifyMonthStart(userId, month, hasData);
        } catch (RuntimeException e) {
            // One user's failure must not cost everyone after them their nudge. Nothing was queued,
            // so the next run tries again.
            log.warn("Could not queue a month-start nudge for user {}; retrying next run.", userId, e);
            return false;
        }
    }

    /**
     * The users every nudge may go to and who have had none this month. Shared by both cases. A user
     * who switched reminders off is left out here, not only by the outbox: nothing is ever queued for
     * them, so they would stay due on every run and could fill the batch ahead of everyone else.
     */
    private static final String ELIGIBLE = """
             u.status = ? AND u.account_scope = ?
             AND EXISTS (SELECT 1 FROM device_tokens d WHERE d.user_id = u.id AND d.revoked_at IS NULL)
             AND NOT EXISTS (SELECT 1 FROM notification_preferences p
                              WHERE p.user_id = u.id AND p.category = 'REMINDERS' AND p.channel = 'PUSH'
                                AND p.enabled = false)
             AND NOT EXISTS (SELECT 1 FROM notifications n
                              WHERE n.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')
            """;

    /**
     * Accounts overdue for a newer statement. The account's latest import must also be more than
     * {@link #RECENT_IMPORT_GRACE_DAYS} old: someone who has just imported an old statement is catching
     * up, and telling them an hour later that "a new statement has probably arrived" would nag.
     */
    List<UUID> hasDataDue(Instant now, LocalDate today, YearMonth month) {
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
                         AND EXISTS (SELECT 1 FROM accounts a
                                      WHERE a.user_id = u.id AND a.deleted_at IS NULL
                                        AND (SELECT MAX(si.statement_period_end) FROM statement_imports si
                                              WHERE si.account_id = a.id AND si.deleted_at IS NULL) < ?
                                        AND (SELECT MAX(si.imported_at) FROM statement_imports si
                                              WHERE si.account_id = a.id AND si.deleted_at IS NULL) < ?)
                         ORDER BY u.id LIMIT ?
                        """, UUID.class,
                User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(),
                Date.valueOf(today.minusDays(STALE_AFTER_DAYS)),
                Timestamp.from(now.minus(Duration.ofDays(RECENT_IMPORT_GRACE_DAYS))), BATCH_SIZE);
    }

    /**
     * Users with nothing imported. Only those who already had an account when this month's nudge
     * became due (the 3rd, 10:00 India time): someone who signs up later in the month waits for the
     * next one rather than getting it within the hour.
     */
    List<UUID> noDataDue(Instant now, YearMonth month) {
        String first = NotificationType.STATEMENT_MONTH_START_FIRST.name();
        String previous = month.minusMonths(1).toString();
        String beforeThat = month.minusMonths(2).toString();
        Instant dueFrom = month.atDay(FIRST_DAY_OF_MONTH).atTime(SEND_FROM).atZone(UserZone.DEFAULT).toInstant();
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
                         AND u.created_at < ?
                         AND NOT EXISTS (SELECT 1 FROM statement_imports si WHERE si.user_id = u.id)
                         AND NOT (
                             EXISTS (SELECT 1 FROM notifications n1 WHERE n1.type = ?
                                       AND n1.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')
                             AND EXISTS (SELECT 1 FROM notifications n2 WHERE n2.type = ?
                                       AND n2.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')
                             AND NOT EXISTS (
                                 SELECT 1 FROM import_funnel_days f
                                  WHERE f.user_id = u.id AND f.event = 'IMPORT_SCREEN_OPENED'
                                    AND f.funnel_date >= (
                                        SELECT (n3.created_at AT TIME ZONE ?)::date FROM notifications n3
                                         WHERE n3.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')))
                         ORDER BY u.id LIMIT ?
                        """, UUID.class,
                User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(), Timestamp.from(dueFrom),
                first, previous, first, beforeThat, UserZone.DEFAULT.getId(), beforeThat, BATCH_SIZE);
    }
}
