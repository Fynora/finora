package com.finora.imports;

import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.service.StatementStatusNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reminds a user, once, that statements they can still confirm will be removed in less than a day.
 *
 * <p>A staged session is deleted by {@link ImportSessionService#sweepExpiredSessions} once its
 * {@code expires_at} passes -- 48 hours after it was read, or after a trust review released it.
 * Nothing told the user beforehand. In October 2026 a tester's statement was released from review,
 * never confirmed, and removed two days later with no word to them; the layout review queue showed
 * several more layouts read cleanly and never confirmed. Sid chose a reminder over a longer window
 * (2026-10-14): the 48 hours stays, and the user hears about it with a day left.
 *
 * <h2>Which sessions</h2>
 * Only a session the user can actually act on: STAGED, unexpired, uploaded by them (a Gmail-staged
 * session was put in front of them, not brought by them -- the same line
 * {@code ImportSessionService.stampAbandoned} draws), of a kind the unfinished-imports list can
 * reopen ({@link ImportSessionService#supportsResume(UUID, String)}), and not withheld by a trust
 * review ({@link ImportSessionService#sessionsBlockedByTrustReview}) -- telling a user to confirm
 * a statement the confirm step refuses would be worse than silence.
 *
 * <h2>Once per window, once per user</h2>
 * {@code confirm_reminder_expires_at} (V269) records the {@code expires_at} a reminder covered. A
 * session is due while the two differ, so a session whose clock is restarted
 * ({@code ImportSessionService.renewExpiry}) gets one reminder for its new window, never two for
 * the same one. The stamp is taken with a conditional UPDATE in the same transaction that queues
 * the notification, so two runs racing each other cannot both claim a session, and a failed queue
 * leaves it due for the next run. The outbox's own key dedup is the second net.
 *
 * <p>Every due session of one user goes into a single push and email ("3 of your statements are
 * ..."): someone who uploaded six months of statements at once and confirmed none should get one
 * reminder, not six.
 */
@Component
public class UnconfirmedImportReminder {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedImportReminder.class);

    /** How long before removal the reminder goes out. */
    static final Duration LEAD = Duration.ofHours(24);

    /** Sessions read per run. A backlog larger than this drains over the next runs. */
    static final int BATCH_SIZE = 500;

    @Value("${app.import.confirm-reminder.enabled:true}")
    private boolean enabled;

    private final JdbcTemplate jdbc;
    private final ImportSessionService sessions;
    private final StatementStatusNotifier notifier;
    private final TransactionTemplate transaction;

    public UnconfirmedImportReminder(JdbcTemplate jdbc, ImportSessionService sessions,
                                     StatementStatusNotifier notifier, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.notifier = notifier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Every 15 minutes by default, so a reminder goes out between 24 hours and 23h45m before
     *  removal. {@code fixedDelay}, as the session sweep uses, so a slow run cannot overlap the next. */
    @Scheduled(fixedDelayString = "${app.import.confirm-reminder.interval-ms:900000}",
            initialDelayString = "${app.import.confirm-reminder.initial-delay-ms:120000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            int users = remindDue(Instant.now());
            if (users > 0) log.info("Sent confirm reminders to {} user(s).", users);
        } catch (RuntimeException e) {
            log.warn("Confirm reminder run failed; the next run will retry.", e);
        }
    }

    /** One run as of {@code now}. Returns how many users were sent a reminder. Does not consult the
     *  enabled flag, so tests can drive it directly. */
    public int remindDue(Instant now) {
        Instant until = now.plus(LEAD);
        // Ids and kinds only: loading the sessions would read every staged file's bytes.
        //
        // The kind and the held-job test are repeated here, ahead of the authoritative checks below,
        // because a session those checks drop is never stamped and so stays due on every run: left
        // to the Java filter alone, enough of them at the front of the window would fill the batch
        // and starve the statements behind them until they expired unreminded.
        List<Due> candidates = jdbc.query("""
                SELECT s.id, s.user_id, s.session_kind FROM import_sessions s
                 WHERE s.status = ?
                   AND s.source IS NULL
                   AND s.session_kind = ?
                   AND s.expires_at > ? AND s.expires_at <= ?
                   AND s.confirm_reminder_expires_at IS DISTINCT FROM s.expires_at
                   AND NOT EXISTS (SELECT 1 FROM import_jobs j
                                    WHERE j.import_session_id = s.id AND j.status = ?)
                 ORDER BY s.expires_at
                 LIMIT ?
                """, (rs, row) -> new Due(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("session_kind")),
                ImportSession.STATUS_STAGED, ImportSession.KIND_SINGLE_ACCOUNT, utc(now), utc(until),
                ImportJob.Status.HELD_FOR_TRUST_REVIEW.name(), BATCH_SIZE);
        List<Due> resumable = candidates.stream()
                .filter(d -> ImportSessionService.supportsResume(d.sessionId(), d.sessionKind()))
                .toList();
        if (resumable.isEmpty()) return 0;
        Set<UUID> withheld = sessions.sessionsBlockedByTrustReview(resumable.stream().map(Due::sessionId).toList());

        Map<UUID, List<UUID>> byUser = new LinkedHashMap<>();
        for (Due d : resumable) {
            if (withheld.contains(d.sessionId())) continue;
            byUser.computeIfAbsent(d.userId(), k -> new ArrayList<>()).add(d.sessionId());
        }

        int reminded = 0;
        for (Map.Entry<UUID, List<UUID>> user : byUser.entrySet()) {
            try {
                Boolean sent = transaction.execute(status -> remindOne(user.getKey(), user.getValue(), now));
                if (Boolean.TRUE.equals(sent)) reminded++;
            } catch (RuntimeException e) {
                // One user's failure must not cost everyone after them their reminder. Their sessions
                // were not stamped (the transaction rolled back), so the next run tries again.
                log.warn("Could not send a confirm reminder to user {}; retrying next run.", user.getKey(), e);
            }
        }
        return reminded;
    }

    /** Claims this user's due sessions and queues one reminder for them, in the caller's
     *  transaction. False when another run claimed them all first. */
    private boolean remindOne(UUID userId, List<UUID> sessionIds, Instant now) {
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        List<Object> args = new ArrayList<>(sessionIds);
        args.add(ImportSession.STATUS_STAGED);
        args.add(utc(now));
        args.add(utc(now.plus(LEAD)));
        // Re-checks the state the candidate read saw. A session confirmed in between is not claimed;
        // nor is one whose clock was restarted in between (an approved review) -- stamping its new
        // expiry now would spend the new window's reminder two days early and leave none for when
        // it is actually due.
        List<OffsetDateTime> claimed = jdbc.query("""
                UPDATE import_sessions
                   SET confirm_reminder_expires_at = expires_at
                 WHERE id IN (%s)
                   AND status = ?
                   AND expires_at > ? AND expires_at <= ?
                   AND confirm_reminder_expires_at IS DISTINCT FROM expires_at
                RETURNING expires_at
                """.formatted(placeholders),
                (rs, row) -> rs.getObject("expires_at", OffsetDateTime.class), args.toArray());
        if (claimed.isEmpty()) return false;
        Instant earliest = claimed.stream().map(OffsetDateTime::toInstant).min(Instant::compareTo).orElseThrow();
        notifier.notifyConfirmReminder(userId, claimed.size(), earliest);
        return true;
    }

    /** expires_at is timestamptz; an offset-carrying value binds as the same instant whatever the
     *  JVM's default zone is. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Due(UUID sessionId, UUID userId, String sessionKind) {}
}
