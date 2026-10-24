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
 *       ({@code ActivationFacts.hasEverImported}'s rule): from the 3rd of each month, 10:00 India
 *       time. "Your April statement is out ..." on 3 May.</li>
 *   <li><b>Has data</b> -- some live account whose latest live statement's period ended more than 33
 *       days ago (today minus the period end is 34 days or more), with nothing newer for it: any day
 *       of the month, from 10:00 India time.</li>
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
 *   <li>The REMINDERS push preference is honoured by the outbox, as for every notification.</li>
 *   <li>Never a bank, an account or an amount in the text: it shows on lock screens.</li>
 * </ul>
 *
 * "Sent" in the stop rule means queued in the outbox: a queued push the device never showed still
 * counts as asked.
 */
@Component
public class StatementMonthStartNudge {

    private static final Logger log = LoggerFactory.getLogger(StatementMonthStartNudge.class);

    /** The no-data nudge starts on this day of the month. */
    static final int FIRST_DAY_OF_MONTH = 3;
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
        for (UUID userId : hasDataDue(today, month)) {
            if (send(userId, month, true)) nudged++;
        }
        if (today.getDayOfMonth() >= FIRST_DAY_OF_MONTH) {
            for (UUID userId : noDataDue(month)) {
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

    /** The users every nudge may go to and who have had none this month. Shared by both cases. */
    private static final String ELIGIBLE = """
             u.status = ? AND u.account_scope = ?
             AND EXISTS (SELECT 1 FROM device_tokens d WHERE d.user_id = u.id AND d.revoked_at IS NULL)
             AND NOT EXISTS (SELECT 1 FROM notifications n
                              WHERE n.notification_key = 'STATEMENT_MONTH_START_' || u.id || '_' || ? || ':PUSH')
            """;

    private List<UUID> hasDataDue(LocalDate today, YearMonth month) {
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
                         AND EXISTS (SELECT 1 FROM accounts a
                                      WHERE a.user_id = u.id AND a.deleted_at IS NULL
                                        AND (SELECT MAX(si.statement_period_end) FROM statement_imports si
                                              WHERE si.account_id = a.id AND si.deleted_at IS NULL) < ?)
                         ORDER BY u.id LIMIT ?
                        """, UUID.class,
                User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(),
                Date.valueOf(today.minusDays(STALE_AFTER_DAYS)), BATCH_SIZE);
    }

    private List<UUID> noDataDue(YearMonth month) {
        String first = NotificationType.STATEMENT_MONTH_START_FIRST.name();
        String previous = month.minusMonths(1).toString();
        String beforeThat = month.minusMonths(2).toString();
        return jdbc.queryForList("SELECT u.id FROM users u WHERE " + ELIGIBLE + """
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
                User.STATUS_ACTIVE, User.SCOPE_USER, month.toString(),
                first, previous, first, beforeThat, UserZone.DEFAULT.getId(), beforeThat, BATCH_SIZE);
    }
}
