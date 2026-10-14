package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementStatusNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * The reminder a user gets with less than a day before a statement they can confirm is removed.
 *
 * <p>Real PostgreSQL: the due test and the claim are native SQL on a column the entity does not
 * map, the claim's {@code RETURNING} and {@code IS DISTINCT FROM} are Postgres semantics, and what
 * the user receives is the outbox row rendered from V269's template -- all things a mock cannot
 * stand in for. Each assertion reads only its own user's rows: the sessions table is shared with
 * every other test in the suite.
 */
class UnconfirmedImportReminderIT extends AbstractIntegrationTest {

    @Autowired private UnconfirmedImportReminder reminder;
    @MockitoSpyBean private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessions;
    @Autowired private ImportJobRepository importJobs;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StatementStatusNotifier notifier;

    private User user() {
        User user = new User();
        user.setEmail("confirm-reminder-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirm Reminder User");
        user.setPhoneVerified(true);
        return users.save(user);
    }

    private ImportSession session(User owner, Instant expiresAt) {
        ImportSession session = importSessionService.createSession(
                owner.getId(), "statement.pdf", ("content-" + UUID.randomUUID()).getBytes(), List.of(), null);
        session.setExpiresAt(expiresAt);
        return importSessions.save(session);
    }

    private static Instant inHours(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    private List<Map<String, Object>> reminders(User owner) {
        return jdbc.queryForList("""
                SELECT channel, title, message, notification_key FROM notifications
                 WHERE user_id = ? AND type = 'IMPORT_STATEMENT_CONFIRM_REMINDER' ORDER BY channel
                """, owner.getId());
    }

    @Test
    void aStatementWithLessThanADayLeftGetsOnePushAndOneEmail() {
        User owner = user();
        session(owner, inHours(10));

        reminder.remindDue(Instant.now());

        List<Map<String, Object>> sent = reminders(owner);
        assertThat(sent).extracting(r -> r.get("channel")).containsExactly("EMAIL", "PUSH");
        for (Map<String, Object> row : sent) {
            String text = row.get("title") + " " + row.get("message");
            assertThat(text).as("rendered from V269 with its param").contains("Your statement is")
                    .doesNotContain("{{");
        }
        assertThat((String) sent.get(1).get("message")).contains("Confirm within a day");
    }

    @Test
    void aSecondRunSendsNothingMore() {
        User owner = user();
        session(owner, inHours(10));

        reminder.remindDue(Instant.now());
        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).hasSize(2);
    }

    @Test
    void aStatementWithMoreThanADayLeftIsNotRemindedYet() {
        User owner = user();
        session(owner, inHours(30));

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
    }

    @Test
    void theWindowBoundaryIsExactlyOneDay() {
        User owner = user();
        Instant now = Instant.now();
        ImportSession atLead = session(owner, now.plus(UnconfirmedImportReminder.LEAD));
        ImportSession pastLead = session(owner, now.plus(UnconfirmedImportReminder.LEAD).plusSeconds(1));

        reminder.remindDue(now);

        assertThat(reminderStampOf(atLead)).as("exactly a day left is due").isNotNull();
        assertThat(reminderStampOf(pastLead)).as("one second more is not").isNull();
    }

    @Test
    void anAlreadyExpiredStatementIsNotReminded() {
        User owner = user();
        session(owner, Instant.now().minus(1, ChronoUnit.MINUTES));

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
    }

    @Test
    void aConfirmedStatementIsNotReminded() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        session.setStatus(ImportSession.STATUS_CONFIRMED);
        session.setConfirmedAt(Instant.now());
        importSessions.save(session);

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
    }

    @Test
    void severalWaitingStatementsGetOneReminderThatCountsThem() {
        User owner = user();
        session(owner, inHours(5));
        session(owner, inHours(12));
        session(owner, inHours(20));

        reminder.remindDue(Instant.now());

        List<Map<String, Object>> sent = reminders(owner);
        assertThat(sent).hasSize(2);
        assertThat((String) sent.get(1).get("message")).startsWith("3 of your statements are");
    }

    @Test
    void aStatementTheUnfinishedListCannotReopenIsNotReminded() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        session.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        importSessions.save(session);

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
    }

    @Test
    void aStatementTheUserDidNotUploadIsNotReminded() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        session.setSource(ImportSession.SOURCE_GMAIL);
        importSessions.save(session);

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
    }

    /** Telling a user to confirm what the confirm step refuses would be worse than saying nothing. */
    @Test
    void aStatementStillHeldForReviewIsNotReminded() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf", "hash-" + UUID.randomUUID(),
                "objects/key-" + UUID.randomUUID(), "PDF");
        job.holdForTrustReview(session.getId(), null, Instant.now());
        importJobs.save(job);

        reminder.remindDue(Instant.now());

        assertThat(reminders(owner)).isEmpty();
        assertThat(reminderStampOf(session)).as("and stays due for when the hold is released").isNull();
    }

    /** An approved review restarts the 48 hours (ImportSessionService.renewExpiry); the new window
     *  gets its own reminder. */
    @Test
    void aRestartedWindowGetsItsOwnReminder() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        reminder.remindDue(Instant.now());
        assertThat(reminders(owner)).hasSize(2);

        importSessionService.renewExpiry(session.getId());
        reminder.remindDue(Instant.now());
        assertThat(reminders(owner)).as("48 hours left again: not due yet").hasSize(2);

        // The new window running down to its last day. Moved on the row rather than by running the
        // reminder 30 hours ahead: that run would also pick up every other test's 48-hour session,
        // and the batch limit could leave this one out.
        jdbc.update("UPDATE import_sessions SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(inHours(11)), session.getId());
        reminder.remindDue(Instant.now());
        assertThat(reminders(owner)).as("a day left in the new window").hasSize(4);
    }

    /** A failure queueing one user's reminder rolls back their claim, so they are retried, and does
     *  not cost anyone after them their reminder. */
    @Test
    void oneUsersFailureLeavesThemDueAndDoesNotStopTheOthers() {
        User failing = user();
        User fine = user();
        ImportSession failingSession = session(failing, inHours(2));
        session(fine, inHours(3));
        doThrow(new IllegalStateException("outbox unreachable"))
                .when(notifier).notifyConfirmReminder(eq(failing.getId()), anyInt(), any());

        reminder.remindDue(Instant.now());

        assertThat(reminders(failing)).isEmpty();
        assertThat(reminderStampOf(failingSession)).as("claim rolled back with the failure").isNull();
        assertThat(reminders(fine)).hasSize(2);

        doCallRealMethod().when(notifier).notifyConfirmReminder(eq(failing.getId()), anyInt(), any());
        reminder.remindDue(Instant.now());
        assertThat(reminders(failing)).as("retried on the next run").hasSize(2);
    }

    /** A window restarted after the due read but before the claim (an approval landing mid-run)
     *  must not have its new window's reminder spent now, two days early. */
    @Test
    void aWindowRestartedMidRunIsNotClaimedEarly() {
        User owner = user();
        ImportSession session = session(owner, inHours(10));
        // The trust-review lookup runs between the due read and the claim -- the gap to land in.
        doAnswer(invocation -> {
            importSessionService.renewExpiry(session.getId());
            return invocation.callRealMethod();
        }).when(importSessionService).sessionsBlockedByTrustReview(anyCollection());

        reminder.remindDue(Instant.now());

        assertThat(importSessions.findById(session.getId()).orElseThrow().getExpiresAt())
                .as("the window really was restarted mid-run").isAfter(inHours(40));
        assertThat(reminders(owner)).isEmpty();
        assertThat(reminderStampOf(session)).as("still due when the new window's last day comes").isNull();
    }

    /** Sessions the reminder can never send for (here: kinds the unfinished list cannot reopen)
     *  are never stamped, so they stay due. A batch's worth of them due first must not starve a
     *  statement the user can confirm. */
    @Test
    void aFullBatchOfStatementsThatCannotBeRemindedDoesNotStarveOneThatCan() {
        User crowd = user();
        User owner = user();
        ImportSession template = session(crowd, inHours(1));
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'import_sessions' AND table_schema = current_schema()
                 ORDER BY ordinal_position
                """, String.class);
        String select = columns.stream().map(c -> switch (c) {
            case "id" -> "gen_random_uuid()";
            case "session_kind" -> "'" + ImportSession.KIND_MULTI_ACCOUNT + "'";
            case "content_hash", "object_key" -> "NULL";
            default -> "s." + c;
        }).collect(java.util.stream.Collectors.joining(", "));
        jdbc.update("INSERT INTO import_sessions (" + String.join(", ", columns) + ") SELECT " + select
                + " FROM import_sessions s CROSS JOIN generate_series(1, ?) WHERE s.id = ?",
                UnconfirmedImportReminder.BATCH_SIZE + 1, template.getId());
        importSessions.delete(template);
        session(owner, inHours(20));

        try {
            reminder.remindDue(Instant.now());

            assertThat(reminders(owner)).hasSize(2);
        } finally {
            jdbc.update("DELETE FROM import_sessions WHERE user_id = ?", crowd.getId());
        }
    }

    private Instant reminderStampOf(ImportSession session) {
        java.sql.Timestamp stamp = jdbc.queryForObject(
                "SELECT confirm_reminder_expires_at FROM import_sessions WHERE id = ?",
                java.sql.Timestamp.class, session.getId());
        return stamp == null ? null : stamp.toInstant();
    }
}
