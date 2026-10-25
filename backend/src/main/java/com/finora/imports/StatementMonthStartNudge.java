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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
 *   <li><b>Has data</b> -- some live savings or credit card account (the ones a bank sends a monthly
 *       statement for) whose latest live statement's period ended more than 33
 *       days ago (today minus the period end is 34 days or more), with nothing newer for it and no
 *       import into it in the last 3 days: any day of the month, from 10:00 India time.</li>
 * </ul>
 *
 * <h2>Limits</h2>
 * <ul>
 *   <li>At most one nudge per user per calendar month, across both cases and every account: the two
 *       notification types share one outbox key per user per month, so a rerun -- a restart, or a
 *       second instance -- inserts nothing ({@link StatementStatusNotifier#notifyMonthStart}).</li>
 *   <li>Each case stops after two months in a row with no answer: a user who was sent that case's
 *       nudge in each of the previous two months, and has opened the import screen on no day since
 *       the first of those two, is skipped. Opening the import screen starts it again. For the
 *       has-data case (Sid, 2026-10-25) this is what stops an account the user no longer uses from
 *       being asked about every month.</li>
 *   <li>Only USER-scope, ACTIVE accounts with a live device token: the nudge is a push, and a user
 *       with no phone would only collect dead-lettered rows.</li>
 *   <li>A user who switched REMINDERS push off is never a candidate (and the outbox would refuse it
 *       anyway). Unset, it follows their "Push notifications" switch (Sid, 2026-10-25).</li>
 *   <li>Nobody with a statement already on its way: being read, held for our check, or staged and
 *       waiting for them to confirm.</li>
 *   <li>An account a live Account Aggregator link owns never counts as overdue.</li>
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
     * them, so they would stay due on every run and could fill the batch ahead of everyone else. An
     * unset reminders switch follows the user's "Push notifications" (FINANCIAL push) choice, the
     * same value the outbox applies ({@code DatabaseNotificationPreferenceResolver}).
     *
     * <p>Also left out: a user with a statement already on its way. An import job not yet COMPLETED,
     * FAILED or CANCELLED is still being read or is held for our check, where the user was told
     * "we'll notify you as soon as it's done"; asking them for a statement then would contradict
     * that. (A status added later counts as on its way, the quieter side.) A staged import that has
     * not expired waits for them to confirm, which {@code UnconfirmedImportReminder} reminds them of.
     * A failed import is over: the nudge is how they hear to try again.
     */
    private static final String ELIGIBLE = """
             u.status = ? AND u.account_scope = ?
             AND EXISTS (SELECT 1 FROM device_tokens d WHERE d.user_id = u.id AND d.revoked_at IS NULL)
             AND COALESCE(
                     (SELECT p.enabled FROM notification_preferences p
                       WHERE p.user_id = u.id AND p.category = 'REMINDERS' AND p.channel = 'PUSH'),
                     (SELECT p.enabled FROM notification_preferences p
                       WHERE p.user_id = u.id AND p.category = 'FINANCIAL' AND p.channel = 'PUSH'),
                     true)
             AND NOT EXISTS (SELECT 1 FROM notifications n
                              WHERE n.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')
             AND NOT EXISTS (SELECT 1 FROM import_jobs j
                              WHERE j.user_id = u.id AND j.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED'))
             AND NOT EXISTS (SELECT 1 FROM import_sessions s
                              WHERE s.user_id = u.id AND s.status = 'STAGED' AND s.expires_at > ?)
            """;

    /**
     * The stop rule, one case at a time (its own type): a user sent this case's nudge in each of the
     * previous two months, who has opened the import screen on no day since the first of those two,
     * is skipped. Opening the import screen starts it again. Parameters: type, previous month, type,
     * the month before that, the India zone id, the month before that.
     */
    private static final String NOT_STOPPED = """
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
            """;

    private static Object[] notStoppedArgs(NotificationType type, YearMonth month) {
        String previous = month.minusMonths(1).toString();
        String beforeThat = month.minusMonths(2).toString();
        return new Object[] {type.name(), previous, type.name(), beforeThat, UserZone.DEFAULT.getId(), beforeThat};
    }

    private static Object[] args(Object[] head, Object[] stopRule, Object... tail) {
        Object[] all = new Object[head.length + stopRule.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(stopRule, 0, all, head.length, stopRule.length);
        System.arraycopy(tail, 0, all, head.length + stopRule.length, tail.length);
        return all;
    }

    /**
     * Savings and credit card accounts overdue for a newer statement; a wallet or investment account
     * does not get a monthly statement (Sid, 2026-10-25). The account's latest import must also be more than
     * {@link #RECENT_IMPORT_GRACE_DAYS} old: someone who has just imported an old statement is catching
     * up, and telling them an hour later that "a new statement has probably arrived" would nag.
     * An account a live Account Aggregator link owns is left out: its data arrives by the link, and
     * the import screen refuses an upload into it ({@code AccountAggregatorGuard}).
     */
    List<UUID> hasDataDue(Instant now, LocalDate today, YearMonth month) {
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
                         AND EXISTS (SELECT 1 FROM accounts a
                                      WHERE a.user_id = u.id AND a.deleted_at IS NULL
                                        AND a.account_type IN ('SAVINGS', 'CREDIT_CARD')
                                        AND a.primary_source <> 'ACCOUNT_AGGREGATOR'
                                        AND (SELECT MAX(si.statement_period_end) FROM statement_imports si
                                              WHERE si.account_id = a.id AND si.deleted_at IS NULL) < ?
                                        AND (SELECT MAX(si.imported_at) FROM statement_imports si
                                              WHERE si.account_id = a.id AND si.deleted_at IS NULL) < ?)
                        """ + NOT_STOPPED + " ORDER BY u.id LIMIT ?", UUID.class,
                args(new Object[] {User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(), utc(now),
                                today.minusDays(STALE_AFTER_DAYS),
                                utc(now.minus(Duration.ofDays(RECENT_IMPORT_GRACE_DAYS)))},
                        notStoppedArgs(NotificationType.STATEMENT_MONTH_START_NEXT, month), BATCH_SIZE));
    }

    /**
     * Users with nothing imported. Only those who already had an account when this month's nudge
     * became due (the 3rd, 10:00 India time): someone who signs up later in the month waits for the
     * next one rather than getting it within the hour.
     */
    List<UUID> noDataDue(Instant now, YearMonth month) {
        Instant dueFrom = month.atDay(FIRST_DAY_OF_MONTH).atTime(SEND_FROM).atZone(UserZone.DEFAULT).toInstant();
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
                         AND u.created_at < ?
                         AND NOT EXISTS (SELECT 1 FROM statement_imports si WHERE si.user_id = u.id)
                        """ + NOT_STOPPED + " ORDER BY u.id LIMIT ?", UUID.class,
                args(new Object[] {User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(), utc(now), utc(dueFrom)},
                        notStoppedArgs(NotificationType.STATEMENT_MONTH_START_FIRST, month), BATCH_SIZE));
    }

    /** java.time for the driver, as the rest of the code passes instants (ProductionCodeHygieneTest). */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
