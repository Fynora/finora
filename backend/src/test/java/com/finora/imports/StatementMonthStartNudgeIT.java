package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2b spec §6: the month-start nudge. Driven at fixed instants in 2031 so its months never meet
 * real data. The IT tables are shared across the suite, so every assertion is about this test's own
 * users, never about how many users a run notified.
 */
class StatementMonthStartNudgeIT extends AbstractIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private StatementMonthStartNudge nudge;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;

    private static Instant ist(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(IST).toInstant();
    }

    private static final Instant MAY_3_1030 = ist(2031, 5, 3, 10, 30);
    private static final LocalDate MAY_3 = LocalDate.of(2031, 5, 3);

    private User user() {
        User user = new User();
        user.setEmail("month-start-nudge-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Month Start Nudge IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User userWithPhone() {
        User user = user();
        deviceToken(user);
        return user;
    }

    private void deviceToken(User user) {
        jdbc.update("INSERT INTO device_tokens (id, user_id, platform, encrypted_token, encryption_key_id, "
                        + "token_fingerprint, created_at, last_seen_at) VALUES (gen_random_uuid(), ?, 'ANDROID', "
                        + "'synthetic-ciphertext', 'v1', ?, now(), now())",
                user.getId(), UUID.randomUUID().toString().replace("-", ""));
    }

    private UUID account(User user, String name) {
        return account(user, name, Account.Type.SAVINGS);
    }

    private UUID account(User user, String name, Account.Type type) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName(name);
        account.setAccountType(type);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account).getId();
    }

    private void statementImportedAt(User user, UUID accountId, LocalDate periodEnd, Instant importedAt) {
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, statement_period_end, "
                        + "imported_at) VALUES (?, ?, ?, ?, ?, ?)",
                user.getId(), accountId, "statement.csv", "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8),
                Date.valueOf(periodEnd), Timestamp.from(importedAt));
    }

    private void statement(User user, UUID accountId, LocalDate periodEnd) {
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, statement_period_end) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.getId(), accountId, "statement.csv", "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8),
                Date.valueOf(periodEnd));
    }

    /** A nudge an earlier month's run queued, as that run would have written it. */
    private void earlierNudge(User user, String yearMonth, LocalDate sentOn) {
        earlierNudge(user, yearMonth, sentOn, "STATEMENT_MONTH_START_FIRST");
    }

    private void earlierNudge(User user, String yearMonth, LocalDate sentOn, String type) {
        jdbc.update("INSERT INTO notifications (id, user_id, notification_key, type, category, channel, priority, "
                        + "title, message, next_attempt_at, created_at) VALUES (gen_random_uuid(), ?, ?, "
                        + "?, 'REMINDERS', 'PUSH', 'NORMAL', 't', 'm', now(), ?)",
                user.getId(), "STATEMENT_MONTH_START_" + user.getId() + "_" + yearMonth + ":PUSH", type,
                Timestamp.from(sentOn.atTime(10, 30).atZone(IST).toInstant()));
    }

    private void openedImportScreen(User user, LocalDate day) {
        jdbc.update("INSERT INTO import_funnel_days (user_id, event, funnel_date) VALUES (?, 'IMPORT_SCREEN_OPENED', ?)",
                user.getId(), Date.valueOf(day));
    }

    private List<Map<String, Object>> nudgesOf(User user) {
        return jdbc.queryForList("SELECT notification_key, type, category, channel, priority, title, message, created_at "
                + "FROM notifications WHERE user_id = ? AND type LIKE 'STATEMENT_MONTH_START%' "
                + "AND notification_key NOT LIKE '%_2031-03:%' AND notification_key NOT LIKE '%_2031-04:%'", user.getId());
    }

    @Test
    void noDataUserNudgedOnTheThird() {
        User user = userWithPhone();

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).singleElement().satisfies(n -> {
            assertThat(n.get("notification_key")).isEqualTo("STATEMENT_MONTH_START_" + user.getId() + "_2031-05:PUSH");
            assertThat(n.get("type")).isEqualTo("STATEMENT_MONTH_START_FIRST");
            assertThat(n.get("category")).isEqualTo("REMINDERS");
            assertThat(n.get("channel")).isEqualTo("PUSH");
            // A month-start batch waits behind everyone's own notifications ("your statement is ready").
            assertThat(n.get("priority")).isEqualTo("LOW");
            assertThat(n.get("title")).isEqualTo("Your April statement is out");
            assertThat(n.get("message")).isEqualTo(
                    "Your April statement is out. It's a good one to start with, and takes a couple of minutes.");
        });
    }

    @Test
    void notBeforeTenIstOnTheThird() {
        User user = userWithPhone();

        nudge.runDue(ist(2031, 5, 3, 9, 59));
        nudge.runDue(ist(2031, 5, 2, 12, 0));

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void hasDataUserNudgedWhenAnAccountIs34DaysStale() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), MAY_3.minusDays(34));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).singleElement().satisfies(n -> {
            assertThat(n.get("type")).isEqualTo("STATEMENT_MONTH_START_NEXT");
            assertThat(n.get("title")).isEqualTo("A new statement");
            assertThat(n.get("message")).isEqualTo(
                    "A new statement has probably arrived for one of your accounts. Add it to keep your months complete.");
        });
    }

    @Test
    void notWhenTheLatestStatementEndedExactly33DaysAgo() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), MAY_3.minusDays(33));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void hasDataNudgeIsNotTiedToTheThird() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), LocalDate.of(2031, 5, 20).minusDays(40));

        nudge.runDue(ist(2031, 5, 20, 11, 0));

        assertThat(nudgesOf(user)).singleElement()
                .satisfies(n -> assertThat(n.get("type")).isEqualTo("STATEMENT_MONTH_START_NEXT"));
    }

    @Test
    void notWhenTheAccountHasANewerStatement() {
        User user = userWithPhone();
        UUID account = account(user, "Synthetic Savings");
        statement(user, account, MAY_3.minusDays(60));
        statement(user, account, MAY_3.minusDays(10));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void secondRunSameMonthSendsNothing() {
        User noData = userWithPhone();
        User hasData = userWithPhone();
        statement(hasData, account(hasData, "Synthetic Savings"), MAY_3.minusDays(40));

        nudge.runDue(MAY_3_1030);
        nudge.runDue(ist(2031, 5, 3, 11, 30));
        nudge.runDue(ist(2031, 5, 28, 11, 30));

        assertThat(nudgesOf(noData)).hasSize(1);
        assertThat(nudgesOf(hasData)).hasSize(1);
    }

    @Test
    void oneNudgePerUserAcrossCasesAndAccounts() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), MAY_3.minusDays(40));
        statement(user, account(user, "Synthetic Card"), MAY_3.minusDays(70));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).singleElement()
                .satisfies(n -> assertThat(n.get("type")).isEqualTo("STATEMENT_MONTH_START_NEXT"));
    }

    @Test
    void aUserWhoImportedAndDeletedEverythingIsNotTreatedAsNew() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), MAY_3.minusDays(5));
        jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE user_id = ?", user.getId());

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void stopsAfterTwoUnansweredMonths() {
        User user = userWithPhone();
        earlierNudge(user, "2031-03", LocalDate.of(2031, 3, 3));
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 3));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void restartsAfterImportScreenOpened() {
        User user = userWithPhone();
        earlierNudge(user, "2031-03", LocalDate.of(2031, 3, 3));
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 3));
        openedImportScreen(user, LocalDate.of(2031, 4, 10));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).hasSize(1);
    }

    @Test
    void anImportScreenVisitBeforeTheUnansweredNudgesDoesNotRestartThem() {
        User user = userWithPhone();
        openedImportScreen(user, LocalDate.of(2031, 2, 10));
        earlierNudge(user, "2031-03", LocalDate.of(2031, 3, 3));
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 3));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void oneUnansweredMonthDoesNotStopIt() {
        User user = userWithPhone();
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 3));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).hasSize(1);
    }

    @Test
    void skipsRemindersOptOut() {
        User optedOut = userWithPhone();
        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                + "VALUES (gen_random_uuid(), ?, 'REMINDERS', 'PUSH', false)", optedOut.getId());
        // Sid (2026-10-25): an unset reminders switch follows "Push notifications"; one the user set
        // themselves wins.
        User pushOff = userWithPhone();
        preference(pushOff, "FINANCIAL", false);
        User pushOffRemindersOn = userWithPhone();
        preference(pushOffRemindersOn, "FINANCIAL", false);
        preference(pushOffRemindersOn, "REMINDERS", true);

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(optedOut)).isEmpty();
        assertThat(nudgesOf(pushOff)).isEmpty();
        assertThat(nudgesOf(pushOffRemindersOn)).hasSize(1);
    }

    @Test
    void skipsAdminScopeInactiveAndPhoneless() {
        User admin = user();
        admin.setAccountScope(User.SCOPE_ADMIN);
        userRepository.save(admin);
        deviceToken(admin);
        User deactivated = user();
        deactivated.setStatus(User.STATUS_DEACTIVATED);
        userRepository.save(deactivated);
        deviceToken(deactivated);
        User noPhone = user();
        User revoked = user();
        deviceToken(revoked);
        jdbc.update("UPDATE device_tokens SET revoked_at = now() WHERE user_id = ?", revoked.getId());

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(admin)).isEmpty();
        assertThat(nudgesOf(deactivated)).isEmpty();
        assertThat(nudgesOf(noPhone)).isEmpty();
        assertThat(nudgesOf(revoked)).isEmpty();
    }

    @Test
    void noBankNameInParams() {
        User user = userWithPhone();
        statement(user, account(user, "Example Bank Savings 100000000001"), MAY_3.minusDays(40));

        nudge.runDue(MAY_3_1030);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT params, title, message FROM notifications WHERE user_id = ? AND type LIKE 'STATEMENT_MONTH_START%'",
                user.getId());
        assertThat(rows).singleElement().satisfies(n -> {
            assertThat(String.valueOf(n.get("params"))).doesNotContain("Example").doesNotContain("100000000001");
            assertThat(String.valueOf(n.get("title")) + n.get("message")).doesNotContain("Example");
        });
    }

    // ---- Found in review (2026-10-24): the no-data nudge belongs to the start of the month ----

    /** Spec §6 says the 3rd. Sending on any later day would push every no-data user on the day this
     *  ships, and a user who signed up mid-month within the hour. */
    @Test
    void noDataNudgeIsNotSentMidMonth() {
        User user = userWithPhone();

        nudge.runDue(ist(2031, 5, 20, 11, 0));

        assertThat(nudgesOf(user)).isEmpty();
    }

    /** A run missed on the 3rd (a deploy, an outage) still catches up within the first week. */
    @Test
    void noDataNudgeCatchesUpWithinTheFirstWeek() {
        User user = userWithPhone();

        nudge.runDue(ist(2031, 5, 9, 11, 0));

        assertThat(nudgesOf(user)).hasSize(1);
    }

    @Test
    void aUserWhoSignedUpAfterTheThirdWaitsForNextMonth() {
        User user = userWithPhone();
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(ist(2031, 5, 4, 9, 0)), user.getId());

        nudge.runDue(ist(2031, 5, 5, 11, 0));

        assertThat(nudgesOf(user)).isEmpty();
    }

    // ---- Found in review: someone catching up on old statements is not told a new one arrived ----

    @Test
    void notRightAfterImportingAnOldStatement() {
        User user = userWithPhone();
        statementImportedAt(user, account(user, "Synthetic Savings"), MAY_3.minusDays(60), MAY_3_1030.minusSeconds(86_400));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).isEmpty();
    }

    @Test
    void anOldStatementImportedMoreThanThreeDaysAgoStillCounts() {
        User user = userWithPhone();
        statementImportedAt(user, account(user, "Synthetic Savings"), MAY_3.minusDays(60), MAY_3_1030.minusSeconds(4 * 86_400));

        nudge.runDue(MAY_3_1030);

        assertThat(nudgesOf(user)).hasSize(1);
    }

    // ---- Found in review: a user who switched reminders off is never a candidate ----

    /** Otherwise they stay due every hour of every month (nothing is queued for them), and enough of
     *  them would fill each batch and starve everyone behind them. */
    @Test
    void aUserWhoSwitchedRemindersOffIsNotACandidate() {
        User noData = userWithPhone();
        User hasData = userWithPhone();
        statement(hasData, account(hasData, "Synthetic Savings"), MAY_3.minusDays(40));
        for (User u : List.of(noData, hasData)) {
            jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                    + "VALUES (gen_random_uuid(), ?, 'REMINDERS', 'PUSH', false)", u.getId());
        }

        assertThat(nudge.noDataDue(MAY_3_1030, java.time.YearMonth.of(2031, 5))).doesNotContain(noData.getId());
        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, java.time.YearMonth.of(2031, 5))).doesNotContain(hasData.getId());
    }

    // ---- Found in the second review: a statement already on its way, and an account a live
    // ---- Account Aggregator link owns ----

    private void importJob(User user, String status) {
        jdbc.update("INSERT INTO import_jobs (id, user_id, file_name, status, source_format) "
                + "VALUES (gen_random_uuid(), ?, 'statement.pdf', ?, 'PDF')", user.getId(), status);
    }

    private void stagedSession(User user, Instant expiresAt) {
        jdbc.update("INSERT INTO import_sessions (user_id, file_name, file_content, status, expires_at) "
                + "VALUES (?, 'statement.csv', ?, 'STAGED', ?)", user.getId(),
                "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8), Timestamp.from(expiresAt));
    }

    /** A statement held for our team's check is waiting on us ("we'll notify you as soon as it's
     *  done"); asking the user to add one would contradict that. The same for one still being read. */
    @Test
    void notWhileTheirStatementIsHeldOrStillBeingRead() {
        java.time.YearMonth may = java.time.YearMonth.of(2031, 5);
        User held = userWithPhone();
        importJob(held, "HELD_FOR_TRUST_REVIEW");
        User heldForParser = userWithPhone();
        importJob(heldForParser, "HELD_FOR_REVIEW");
        User reading = userWithPhone();
        statement(reading, account(reading, "Synthetic Savings"), MAY_3.minusDays(40));
        importJob(reading, "PARSING");
        User failed = userWithPhone();
        importJob(failed, "FAILED");

        assertThat(nudge.noDataDue(MAY_3_1030, may)).doesNotContain(held.getId(), heldForParser.getId());
        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, may)).doesNotContain(reading.getId());
        // A failed import is over: the nudge is how they hear to try again.
        assertThat(nudge.noDataDue(MAY_3_1030, may)).contains(failed.getId());
    }

    /** A staged import waiting for them to confirm has its own reminder (UnconfirmedImportReminder). */
    @Test
    void notWhileAStagedImportWaitsForThemToConfirm() {
        java.time.YearMonth may = java.time.YearMonth.of(2031, 5);
        User waiting = userWithPhone();
        stagedSession(waiting, MAY_3_1030.plus(java.time.Duration.ofDays(2)));
        User expired = userWithPhone();
        stagedSession(expired, MAY_3_1030.minus(java.time.Duration.ofDays(1)));

        assertThat(nudge.noDataDue(MAY_3_1030, may)).doesNotContain(waiting.getId()).contains(expired.getId());
    }

    /** The import screen refuses an upload into an account a live Account Aggregator link owns
     *  (AccountAggregatorGuard), so a nudge for it would send them to a refusal. */
    @Test
    void notForAnAccountAnAccountAggregatorLinkOwns() {
        java.time.YearMonth may = java.time.YearMonth.of(2031, 5);
        User linked = userWithPhone();
        UUID linkedAccount = account(linked, "Synthetic Linked");
        statement(linked, linkedAccount, MAY_3.minusDays(40));
        jdbc.update("UPDATE accounts SET primary_source = 'ACCOUNT_AGGREGATOR' WHERE id = ?", linkedAccount);
        User manual = userWithPhone();
        statement(manual, account(manual, "Synthetic Savings"), MAY_3.minusDays(40));

        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, may)).doesNotContain(linked.getId()).contains(manual.getId());
    }

    // ---- Sid's decisions, 2026-10-25 ----

    private void preference(User user, String category, boolean enabled) {
        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                + "VALUES (gen_random_uuid(), ?, ?, 'PUSH', ?)", user.getId(), category, enabled);
    }

    /** Reminders follow "Push notifications" until the user sets them: someone who said "no push"
     *  is not a candidate; one who also switched reminders on is. */
    @Test
    void aUserWhoTurnedOffPushNotificationsIsNotACandidateUnlessTheyWantReminders() {
        java.time.YearMonth may = java.time.YearMonth.of(2031, 5);
        User noPush = userWithPhone();
        preference(noPush, "FINANCIAL", false);
        User noPushStale = userWithPhone();
        statement(noPushStale, account(noPushStale, "Synthetic Savings"), MAY_3.minusDays(40));
        preference(noPushStale, "FINANCIAL", false);
        User remindersOnly = userWithPhone();
        preference(remindersOnly, "FINANCIAL", false);
        preference(remindersOnly, "REMINDERS", true);

        assertThat(nudge.noDataDue(MAY_3_1030, may)).doesNotContain(noPush.getId()).contains(remindersOnly.getId());
        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, may)).doesNotContain(noPushStale.getId());
    }

    /** The "new statement" push stops after two months in a row with no answer, like the no-data
     *  one: an account the user no longer uses would otherwise be asked about every month. */
    @Test
    void theNewStatementNudgeStopsAfterTwoUnansweredMonths() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), LocalDate.of(2031, 2, 10));
        earlierNudge(user, "2031-03", LocalDate.of(2031, 3, 20), "STATEMENT_MONTH_START_NEXT");
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 20), "STATEMENT_MONTH_START_NEXT");

        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, java.time.YearMonth.of(2031, 5))).doesNotContain(user.getId());
    }

    @Test
    void theNewStatementNudgeRestartsAfterTheImportScreenIsOpened() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), LocalDate.of(2031, 2, 10));
        earlierNudge(user, "2031-03", LocalDate.of(2031, 3, 20), "STATEMENT_MONTH_START_NEXT");
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 20), "STATEMENT_MONTH_START_NEXT");
        openedImportScreen(user, LocalDate.of(2031, 4, 25));

        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, java.time.YearMonth.of(2031, 5))).contains(user.getId());
    }

    @Test
    void oneUnansweredNewStatementNudgeDoesNotStopIt() {
        User user = userWithPhone();
        statement(user, account(user, "Synthetic Savings"), LocalDate.of(2031, 2, 10));
        earlierNudge(user, "2031-04", LocalDate.of(2031, 4, 20), "STATEMENT_MONTH_START_NEXT");

        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, java.time.YearMonth.of(2031, 5))).contains(user.getId());
    }

    /** Only savings and credit card accounts get a monthly statement from the bank; a wallet or an
     *  investment account a statement was filed under does not. */
    @Test
    void onlySavingsAndCreditCardAccountsCountAsOverdue() {
        java.time.YearMonth may = java.time.YearMonth.of(2031, 5);
        User wallet = userWithPhone();
        statement(wallet, account(wallet, "Synthetic Wallet", Account.Type.WALLET), MAY_3.minusDays(40));
        User investment = userWithPhone();
        statement(investment, account(investment, "Synthetic Investment", Account.Type.INVESTMENT), MAY_3.minusDays(40));
        User card = userWithPhone();
        statement(card, account(card, "Synthetic Card", Account.Type.CREDIT_CARD), MAY_3.minusDays(40));

        assertThat(nudge.hasDataDue(MAY_3_1030, MAY_3, may))
                .doesNotContain(wallet.getId(), investment.getId()).contains(card.getId());
    }
}
