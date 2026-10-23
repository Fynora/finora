package com.finora.activation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2a spec §6: the activation report. Every count is of real people at their furthest step, so
 * each test builds its own users and finds them by a signup week, an answer range or a month no other
 * test uses: the IT suite shares one database.
 */
class ActivationReportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportFunnelDayRepository funnelDays;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wednesday 5 June 2030, 06:00 UTC: the week starting Monday 3 June 2030, in IST and in UTC. */
    private static final Instant WEEK_A = Instant.parse("2030-06-05T06:00:00Z");

    @Test
    void countsEachUserAtTheirFurthestStep() throws Exception {
        signupAt(WEEK_A);                                                     // SIGNED_UP
        funnel(signupAt(WEEK_A), "IMPORT_SCREEN_OPENED");                     // OPENED_IMPORT
        User chose = signupAt(WEEK_A);
        funnel(chose, "IMPORT_SCREEN_OPENED");
        funnel(chose, "FILE_CHOSEN");                                         // CHOSE_FILE
        session(signupAt(WEEK_A), "REJECTED");                                // REFUSED
        session(signupAt(WEEK_A), "FAILED");                                  // READ_FAILED
        User held = signupAt(WEEK_A);
        session(held, "FAILED");
        job(held, "HELD_FOR_REVIEW");                                         // HELD (above READ_FAILED)
        session(signupAt(WEEK_A), "PARSED");                                  // READ_NOT_REVIEWED
        User reviewed = signupAt(WEEK_A);
        session(reviewed, "PARSED");
        funnel(reviewed, "REVIEW_OPENED");                                    // REVIEWED_NOT_CONFIRMED
        User in = signupAt(WEEK_A);
        session(in, "REJECTED");
        imported(in);                                                         // GOT_DATA_IN
        job(signupAt(WEEK_A), "COMPLETED");                                   // READ_NOT_REVIEWED (queued read)

        JsonNode counts = week(report(admin(), null, null), "2030-06-03").get("counts");

        assertThat(counts.get("SIGNED_UP").asLong()).isEqualTo(1);
        assertThat(counts.get("OPENED_IMPORT").asLong()).isEqualTo(1);
        assertThat(counts.get("CHOSE_FILE").asLong()).isEqualTo(1);
        assertThat(counts.get("REFUSED").asLong()).isEqualTo(1);
        assertThat(counts.get("READ_FAILED").asLong()).isEqualTo(1);
        assertThat(counts.get("HELD").asLong()).isEqualTo(1);
        assertThat(counts.get("READ_NOT_REVIEWED").asLong()).isEqualTo(2);
        assertThat(counts.get("REVIEWED_NOT_CONFIRMED").asLong()).isEqualTo(1);
        assertThat(counts.get("GOT_DATA_IN").asLong()).isEqualTo(1);
    }

    /** A Sunday evening in IST is already Monday: 19:00 UTC is 00:30 IST the next day. */
    @Test
    void groupsBySignupWeekInIst() throws Exception {
        signupAt(Instant.parse("2030-06-16T19:00:00Z")); // Sunday in UTC, Monday 17 June in IST

        JsonNode report = report(admin(), null, null);

        assertThat(week(report, "2030-06-17").get("counts").get("SIGNED_UP").asLong()).isEqualTo(1);
        // The UTC week may exist as a row of zeros (quiet weeks are filled), but this signup is not in it.
        JsonNode utcWeek = findWeek(report, "2030-06-10");
        assertThat(utcWeek == null ? 0 : utcWeek.get("counts").get("SIGNED_UP").asLong()).as("not the UTC week").isZero();
    }

    @Test
    void everyStepIsPresentInAWeekWithZeros() throws Exception {
        signupAt(Instant.parse("2030-07-03T06:00:00Z"));

        JsonNode counts = week(report(admin(), null, null), "2030-07-01").get("counts");

        for (FunnelStep step : FunnelStep.values()) {
            assertThat(counts.has(step.name())).as(step.name()).isTrue();
        }
        assertThat(counts.get("GOT_DATA_IN").asLong()).isZero();
    }

    /** A week with no signups between two that have them is a row of zeros, not a missing row. */
    @Test
    void aQuietWeekBetweenTwoIsShownAsZeros() throws Exception {
        signupAt(Instant.parse("2031-03-05T06:00:00Z")); // week of Monday 3 March 2031
        signupAt(Instant.parse("2031-03-19T06:00:00Z")); // week of Monday 17 March 2031

        JsonNode report = report(admin(), null, null);

        JsonNode quiet = week(report, "2031-03-10");
        for (FunnelStep step : FunnelStep.values()) {
            assertThat(quiet.get("counts").get(step.name()).asLong()).as(step.name()).isZero();
        }
    }

    @Test
    void reportExcludesAdminInternalAndDeletedAccounts() throws Exception {
        Instant weekB = Instant.parse("2030-08-07T06:00:00Z"); // week of Monday 5 August 2030
        imported(signupAt(weekB));                             // the one that counts

        User admin = signupAt(weekB);
        jdbc.update("UPDATE users SET account_scope = 'ADMIN', role = 'ADMIN' WHERE id = ?", admin.getId());
        imported(admin);
        User internal = signupAt(weekB);
        jdbc.update("UPDATE users SET internal_account = true WHERE id = ?", internal.getId());
        imported(internal);
        User pending = signupAt(weekB);
        jdbc.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", pending.getId());
        imported(pending);
        User deleted = signupAt(weekB);
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deleted.getId());
        imported(deleted);

        JsonNode counts = week(report(admin(), null, null), "2030-08-05").get("counts");

        long total = 0;
        for (FunnelStep step : FunnelStep.values()) total += counts.get(step.name()).asLong();
        assertThat(counts.get("GOT_DATA_IN").asLong()).isEqualTo(1);
        assertThat(total).isEqualTo(1);
    }

    /** The range applies to the answers; the funnel and the deletion reasons are not range-bound. */
    @Test
    void answersFilteredByRange() throws Exception {
        User a = signupAt(Instant.parse("2030-09-04T06:00:00Z"));
        User b = signupAt(Instant.parse("2030-09-04T06:00:00Z"));
        User c = signupAt(Instant.parse("2030-09-04T06:00:00Z"));
        answer(a, "NO_FILE", "NOT_NOW", LocalDate.of(2033, 3, 10));
        answer(b, "NO_FILE", "NOT_NOW", LocalDate.of(2033, 3, 20));
        answer(c, "NO_FILE", "NOT_NOW", LocalDate.of(2033, 4, 2)); // outside the range

        JsonNode report = report(admin(), "2033-03-01", "2033-03-31");

        assertThat(answerCount(report, "NO_FILE", "NOT_NOW")).isEqualTo(2);
        // a and b never opened import: SIGNED_UP.
        assertThat(stepAnswerCount(report, "SIGNED_UP", "NO_FILE", "NOT_NOW")).isEqualTo(2);
    }

    @Test
    void answersFromAnExcludedAccountAreNotCounted() throws Exception {
        User internal = signupAt(Instant.parse("2030-09-11T06:00:00Z"));
        jdbc.update("UPDATE users SET internal_account = true WHERE id = ?", internal.getId());
        answer(internal, "REFUSED", "LEAVE_FOR_NOW", LocalDate.of(2033, 6, 10));

        JsonNode report = report(admin(), "2033-06-01", "2033-06-30");

        assertThat(answerCount(report, "REFUSED", "LEAVE_FOR_NOW")).isZero();
    }

    /** Not range-bound and not week-bound, so measured as the change these users make. */
    @Test
    void focusAndTrackingIncludeNone() throws Exception {
        JsonNode before = report(admin(), null, null);

        User answered = signupAt(Instant.parse("2030-10-02T06:00:00Z"));
        jdbc.update("UPDATE users SET spending_tracking_method = 'PAPER', spending_tracking_answered_at = now() WHERE id = ?",
                answered.getId());
        jdbc.update("INSERT INTO user_financial_focus (user_id, focus_key) VALUES (?, 'SAVE_FOR_GOAL')", answered.getId());
        signupAt(Instant.parse("2030-10-02T06:00:00Z")); // answered neither

        JsonNode after = report(admin(), null, null);

        assertThat(label(after, "trackingByStep", "SIGNED_UP", "PAPER") - label(before, "trackingByStep", "SIGNED_UP", "PAPER"))
                .isEqualTo(1);
        assertThat(label(after, "trackingByStep", "SIGNED_UP", "NONE") - label(before, "trackingByStep", "SIGNED_UP", "NONE"))
                .isEqualTo(1);
        assertThat(label(after, "focusByStep", "SIGNED_UP", "SAVE_FOR_GOAL") - label(before, "focusByStep", "SIGNED_UP", "SAVE_FOR_GOAL"))
                .isEqualTo(1);
        assertThat(label(after, "focusByStep", "SIGNED_UP", "NONE") - label(before, "focusByStep", "SIGNED_UP", "NONE"))
                .isEqualTo(1);
    }

    @Test
    void deletionReasonsByMonth() throws Exception {
        jdbc.update("INSERT INTO account_deletion_reasons (reason, deletion_month, had_data) VALUES ('NOTHING_USEFUL', '2032-02-01', true)");
        jdbc.update("INSERT INTO account_deletion_reasons (reason, deletion_month, had_data) VALUES ('NOTHING_USEFUL', '2032-02-01', false)");
        jdbc.update("INSERT INTO account_deletion_reasons (reason, deletion_month, had_data) VALUES ('DONT_NEED_IT', '2032-03-01', false)");

        JsonNode reasons = report(admin(), null, null).get("deletionReasons");

        assertThat(reasonCount(reasons, "2032-02-01", "NOTHING_USEFUL")).isEqualTo(2);
        assertThat(reasonCount(reasons, "2032-03-01", "DONT_NEED_IT")).isEqualTo(1);
    }

    @Test
    void responseHasNoUserIdentifiers() throws Exception {
        User someone = signupAt(Instant.parse("2030-11-06T06:00:00Z"));
        answer(someone, "NO_FILE", "NO_STATEMENT", LocalDate.of(2033, 9, 9));

        ResponseEntity<String> response = get(admin(), "2033-09-01", "2033-09-30");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The report itself; the envelope around it carries the request's own tracing id.
        String data = mapper.readTree(response.getBody()).get("data").toString();
        assertThat(data)
                .doesNotContainPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .doesNotContain("@")
                .doesNotContain(someone.getEmail());
    }

    @Test
    void needsPlatformAnalyticsView() {
        // SUPPORT can view and update users but holds no PLATFORM_ANALYTICS_VIEW (V31).
        assertThat(get(staff("SUPPORT"), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(customerToken(), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void funnelGoLiveIsEarliestDate() throws Exception {
        User early = signupAt(Instant.parse("2030-12-04T06:00:00Z"));
        funnelDays.record(early.getId(), "IMPORT_SCREEN_OPENED", LocalDate.of(2020, 1, 15));

        JsonNode report = report(admin(), null, null);

        LocalDate earliest = jdbc.queryForObject("SELECT min(funnel_date) FROM import_funnel_days", LocalDate.class);
        assertThat(report.get("funnelGoLive").asText()).isEqualTo(earliest.toString());
        assertThat(earliest).isBeforeOrEqualTo(LocalDate.of(2020, 1, 15));
    }

    @Test
    void anInvertedRangeIsRefused() {
        assertThat(get(admin(), "2033-05-01", "2033-04-01").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- by source and second import ----------------------------------------------------------

    /**
     * A UTM value no other test uses isolates its row, so these counts are exact. The fixed buckets
     * (Google, direct, unknown) are shared by every test, so they are measured as the change these
     * users make.
     */
    /** The signup week of the by-source tests' users, so they never land in another test's week. */
    private static final Instant WEEK_S = Instant.parse("2034-01-04T06:00:00Z");

    @Test
    void countsUsersPerSourceAtTheirFurthestStep() throws Exception {
        String tagA = tag("it-src-a-");
        String tagB = tag("it-src-b-");
        JsonNode before = report(admin(), null, null);

        attributed(signupAt(WEEK_S), tagA, null);                             // tagA, SIGNED_UP
        User aIn = signupAt(WEEK_S);
        attributed(aIn, tagA, "www.google.com");                               // utm wins over google
        imported(aIn);                                                         // tagA, GOT_DATA_IN
        User bChose = signupAt(WEEK_S);
        attributed(bChose, tagB, null);
        funnel(bChose, "IMPORT_SCREEN_OPENED");
        funnel(bChose, "FILE_CHOSEN");                                         // tagB, CHOSE_FILE
        User internal = signupAt(WEEK_S);
        attributed(internal, tagA, null);
        jdbc.update("UPDATE users SET internal_account = true WHERE id = ?", internal.getId());
        imported(internal);                                                    // excluded

        User googled = signupAt(WEEK_S);
        attributed(googled, null, "www.google.co.in");
        imported(googled);                                                     // GOOGLE, GOT_DATA_IN
        signupAt(WEEK_S);                                                      // DIRECT: after tracking, no row
        signupAt(Instant.parse("2020-02-05T06:00:00Z"));                       // UNKNOWN: before tracking, no row
        User friend = signupAt(WEEK_S);
        referred(signupAt(WEEK_S), friend);                                    // REFERRAL, after tracking
        referred(signupAt(Instant.parse("2020-02-05T06:00:00Z")), friend);     // REFERRAL, before tracking too
        User taggedReferral = signupAt(WEEK_S);
        attributed(taggedReferral, tagB, null);
        referred(taggedReferral, friend);                                      // tagB: the tag wins

        JsonNode after = report(admin(), null, null);

        JsonNode a = utmRow(after, tagA);
        assertThat(a.get("counts").get("SIGNED_UP").asLong()).isEqualTo(1);
        assertThat(a.get("counts").get("GOT_DATA_IN").asLong()).isEqualTo(1);
        assertThat(stepTotal(a)).as("internal account left out").isEqualTo(2);
        JsonNode b = utmRow(after, tagB);
        assertThat(b.get("counts").get("CHOSE_FILE").asLong()).isEqualTo(1);
        assertThat(stepTotal(b)).isEqualTo(2);
        for (FunnelStep step : FunnelStep.values()) {
            assertThat(b.get("counts").has(step.name())).as(step.name()).isTrue();
        }

        assertThat(kindStep(after, "GOOGLE", "GOT_DATA_IN") - kindStep(before, "GOOGLE", "GOT_DATA_IN")).isEqualTo(1);
        assertThat(kindTotal(after, "GOOGLE") - kindTotal(before, "GOOGLE")).as("the tagged google visit is not here")
                .isEqualTo(1);
        assertThat(kindStep(after, "DIRECT", "SIGNED_UP") - kindStep(before, "DIRECT", "SIGNED_UP"))
                .as("the plain signup and the friend who referred")
                .isEqualTo(2);
        assertThat(kindTotal(after, "REFERRAL") - kindTotal(before, "REFERRAL")).isEqualTo(2);
        assertThat(kindStep(after, "UNKNOWN_BEFORE_TRACKING", "SIGNED_UP")
                - kindStep(before, "UNKNOWN_BEFORE_TRACKING", "SIGNED_UP")).isEqualTo(1);

        // Every fixed bucket is a row, zeros or not; the UTM rows come first.
        java.util.List<String> kinds = new java.util.ArrayList<>();
        for (JsonNode row : after.get("bySource")) kinds.add(row.get("kind").asText());
        assertThat(kinds).containsSubsequence("UTM", "GOOGLE", "BING", "OTHER_SEARCH", "SOCIAL", "OTHER_SITE",
                "REFERRAL", "DIRECT", "UNKNOWN_BEFORE_TRACKING");
        assertThat(kinds.lastIndexOf("UTM")).isLessThan(kinds.indexOf("GOOGLE"));
        // The sources add up to the weeks: every counted user is in exactly one source row.
        long bySourceTotal = 0;
        for (JsonNode row : after.get("bySource")) bySourceTotal += stepTotal(row);
        long byWeekTotal = 0;
        for (JsonNode w : after.get("weeks")) byWeekTotal += stepTotal(w);
        assertThat(bySourceTotal).isEqualTo(byWeekTotal);
    }

    @Test
    void sourceTrackingStartIsWhenV286WasApplied() throws Exception {
        JsonNode report = report(admin(), null, null);

        Instant applied = jdbc.queryForObject(
                "SELECT installed_on::timestamptz FROM flyway_schema_history WHERE version = '286'", Timestamp.class)
                .toInstant();
        Instant start = Instant.parse(report.get("sourceTrackingStart").asText());
        assertThat(start).isEqualTo(applied);
        // Flyway stores installed_on without a zone. Read back, it must be the real moment this
        // run's migrations ran -- not shifted by an offset (IST would be 5.5 hours out).
        Instant dbNow = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
        assertThat(start).isBefore(dbNow).isAfter(dbNow.minus(java.time.Duration.ofHours(4)));
    }

    /** Zero, one and two imports that brought transactions in; an empty import does not count. */
    @Test
    void secondImportCountsTwoDistinctImportsWithTransactions() throws Exception {
        String tag = tag("it-second-");
        JsonNode before = report(admin(), null, null);
        Instant t0 = Instant.parse("2021-06-06T06:00:00Z");

        attributed(signupAt(WEEK_S), tag, null);                               // zero imports
        User one = signupAt(WEEK_S);
        attributed(one, tag, null);
        importedAt(one, t0, 12);                                               // one import
        User oneAndEmpty = signupAt(WEEK_S);
        attributed(oneAndEmpty, tag, null);
        importedAt(oneAndEmpty, t0, 5);
        importedAt(oneAndEmpty, t0.plus(java.time.Duration.ofDays(2)), 0);     // nothing came in: not a second
        User two = signupAt(WEEK_S);
        attributed(two, tag, null);
        importedAt(two, t0, 30);
        importedAt(two, t0.plus(java.time.Duration.ofDays(1)), 25);            // second after 1 day
        User three = signupAt(WEEK_S);
        attributed(three, tag, null);
        importedAt(three, t0.plus(java.time.Duration.ofDays(3)), 1);           // out of order on purpose
        importedAt(three, t0, 8);
        importedAt(three, t0.plus(java.time.Duration.ofDays(9)), 4);           // the third does not matter

        JsonNode after = report(admin(), null, null);

        JsonNode row = utmRow(after, tag);
        assertThat(row.get("counts").get("GOT_DATA_IN").asLong()).isEqualTo(4);
        assertThat(row.get("secondImport").get("users").asLong()).isEqualTo(2);
        assertThat(row.get("secondImport").get("medianDays").asDouble()).as("median of 1 and 3 days").isEqualTo(2.0);
        assertThat(row.get("secondImport").get("cameBackLaterDay").asLong()).isEqualTo(2);
        assertThat(after.get("secondImport").get("users").asLong() - before.get("secondImport").get("users").asLong())
                .isEqualTo(2);
    }

    /**
     * Files, not rows. One composite statement writes a row per account section and a re-import
     * writes another row, both with the same content_hash; neither is a second import. Two
     * different statements sent the same afternoon are a second import but not a return.
     */
    @Test
    void secondImportCountsDistinctFilesNotRows() throws Exception {
        String tag = tag("it-files-");
        Instant t0 = Instant.parse("2021-06-06T06:00:00Z");

        User composite = signupAt(WEEK_S);
        attributed(composite, tag, null);
        String compositeFile = hash();
        importedFile(composite, t0, 20, compositeFile);                        // savings section
        importedFile(composite, t0, 7, compositeFile);                         // card section, same file
        User reimport = signupAt(WEEK_S);
        attributed(reimport, tag, null);
        String reimported = hash();
        importedFile(reimport, t0, 20, reimported);
        importedFile(reimport, t0.plus(java.time.Duration.ofDays(3)), 20, reimported); // same file again
        User sameAfternoon = signupAt(WEEK_S);
        attributed(sameAfternoon, tag, null);
        importedFile(sameAfternoon, t0, 10, hash());
        importedFile(sameAfternoon, t0.plus(java.time.Duration.ofHours(2)), 6, hash()); // second, same IST day
        User returned = signupAt(WEEK_S);
        attributed(returned, tag, null);
        importedFile(returned, t0, 10, hash());
        importedFile(returned, t0.plus(java.time.Duration.ofDays(4)), 9, hash());       // second, 4 days on

        JsonNode second = utmRow(report(admin(), null, null), tag).get("secondImport");

        assertThat(second.get("users").asLong()).as("same-afternoon and returned only").isEqualTo(2);
        assertThat(second.get("cameBackLaterDay").asLong()).as("returned only").isEqualTo(1);
        // Median of 2 hours (0.083 days) and 4 days.
        assertThat(second.get("medianDays").asDouble()).isEqualTo(2.0);
    }

    /** 23:00 and 01:00 IST are two days, though only two hours apart (and one UTC day). */
    @Test
    void cameBackLaterDayUsesTheIstDay() throws Exception {
        String tag = tag("it-istday-");
        User user = signupAt(WEEK_S);
        attributed(user, tag, null);
        importedFile(user, Instant.parse("2021-06-06T17:30:00Z"), 4, hash()); // 23:00 IST, 6 June
        importedFile(user, Instant.parse("2021-06-06T19:30:00Z"), 4, hash()); // 01:00 IST, 7 June

        JsonNode second = utmRow(report(admin(), null, null), tag).get("secondImport");

        assertThat(second.get("users").asLong()).isEqualTo(1);
        assertThat(second.get("cameBackLaterDay").asLong()).isEqualTo(1);
    }

    @Test
    void secondImportIsZeroWithNoMedianWhenNobodyHasOne() throws Exception {
        String tag = tag("it-none-");
        User one = signupAt(WEEK_S);
        attributed(one, tag, null);
        importedAt(one, Instant.parse("2021-06-06T06:00:00Z"), 3);

        JsonNode row = utmRow(report(admin(), null, null), tag);

        assertThat(row.get("secondImport").get("users").asLong()).isZero();
        assertThat(row.get("secondImport").get("medianDays").isNull()).isTrue();
        assertThat(row.get("secondImport").get("cameBackLaterDay").asLong()).isZero();
    }

    @Test
    void secondImportLeavesOutExcludedAccounts() throws Exception {
        String tag = tag("it-excl-");
        JsonNode before = report(admin(), null, null);
        User internal = signupAt(WEEK_S);
        attributed(internal, tag, null);
        jdbc.update("UPDATE users SET internal_account = true WHERE id = ?", internal.getId());
        importedAt(internal, Instant.parse("2021-06-06T06:00:00Z"), 3);
        importedAt(internal, Instant.parse("2021-06-08T06:00:00Z"), 3);

        JsonNode after = report(admin(), null, null);

        assertThat(after.get("secondImport").get("users").asLong()).isEqualTo(before.get("secondImport").get("users").asLong());
        assertThat(findUtmRow(after, tag)).as("no row for a source only an internal account came from").isNull();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private User signupAt(Instant createdAt) {
        User user = new User();
        user.setEmail("activation-report-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Activation Report IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        return saved;
    }

    private void funnel(User user, String event) {
        funnelDays.record(user.getId(), event, LocalDate.of(2030, 6, 5));
    }

    private void session(User user, String outcome) {
        jdbc.update("INSERT INTO statement_analysis_sessions (id, user_id, reference, source, outcome) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'CUSTOMER_IMPORT', ?)",
                user.getId(), "AR-" + UUID.randomUUID().toString().substring(0, 18), outcome);
    }

    private void job(User user, String status) {
        jdbc.update("INSERT INTO import_jobs (id, user_id, file_name, status, source_format) "
                + "VALUES (gen_random_uuid(), ?, 'statement.csv', ?, 'CSV')", user.getId(), status);
    }

    private void imported(User user) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Activation Report IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content) VALUES (?, ?, ?, ?)",
                user.getId(), accountId, "statement.csv", "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));
    }

    /*
     * Import times in these tests are in the past (2021) on purpose. The admin "recent imports"
     * list orders every statement_imports row newest first and keeps the top few, and the IT suite
     * shares one database: rows dated in the future would push other tests' imports off it
     * (AdminSystemControllerIT failed that way).
     */

    /** A different file each call, with no content_hash: the inline-bytes path older rows take. */
    private void importedAt(User user, Instant at, int transactions) {
        importedFile(user, at, transactions, null);
    }

    /**
     * A confirmed statement row. With {@code contentHash} set, rows sharing it are one file -- the
     * sections of a composite statement, or a re-import -- whatever their bytes.
     */
    private void importedFile(User user, Instant at, int transactions, String contentHash) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Activation Report IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        byte[] bytes = ("Date,Description,Amount\n" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, content_hash, "
                        + "transactions_imported, imported_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.getId(), accountId, "statement.csv", bytes, contentHash, transactions, Timestamp.from(at));
    }

    private static String hash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    /** A tag no other test uses, letters only: a run of digits would read as a phone number and be withheld. */
    private static String tag(String prefix) {
        StringBuilder out = new StringBuilder(prefix);
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) out.append((char) ('a' + random.nextInt(26)));
        return out.toString();
    }

    private void referred(User user, User by) {
        jdbc.update("INSERT INTO referrals (referrer_user_id, referred_user_id, status) VALUES (?, ?, 'PENDING')",
                by.getId(), user.getId());
    }

    private void attributed(User user, String utmSource, String referrerHost) {
        jdbc.update("INSERT INTO signup_attributions (user_id, utm_source, referrer_host) VALUES (?, ?, ?)",
                user.getId(), utmSource, referrerHost);
    }

    private static JsonNode findUtmRow(JsonNode report, String utmSource) {
        for (JsonNode row : report.get("bySource")) {
            if ("UTM".equals(row.get("kind").asText()) && utmSource.equals(row.get("utmSource").asText())) return row;
        }
        return null;
    }

    private static JsonNode utmRow(JsonNode report, String utmSource) {
        JsonNode row = findUtmRow(report, utmSource);
        assertThat(row).as("utm row " + utmSource).isNotNull();
        return row;
    }

    private static long kindStep(JsonNode report, String kind, String step) {
        for (JsonNode row : report.get("bySource")) {
            if (kind.equals(row.get("kind").asText())) return row.get("counts").get(step).asLong();
        }
        throw new AssertionError("no " + kind + " row");
    }

    private static long kindTotal(JsonNode report, String kind) {
        for (JsonNode row : report.get("bySource")) {
            if (kind.equals(row.get("kind").asText())) return stepTotal(row);
        }
        throw new AssertionError("no " + kind + " row");
    }

    private static long stepTotal(JsonNode row) {
        long total = 0;
        for (FunnelStep step : FunnelStep.values()) total += row.get("counts").get(step.name()).asLong();
        return total;
    }

    private void answer(User user, String moment, String answer, LocalDate on) {
        jdbc.update("INSERT INTO activation_answers (user_id, moment, answer, answer_date) VALUES (?, ?, ?, ?)",
                user.getId(), moment, answer, on);
    }

    private User admin() {
        return staff("ADMIN");
    }

    private User staff(String role) {
        User user = new User();
        user.setEmail("activation-report-it-staff-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Activation Report Staff");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User customerToken() {
        User user = new User();
        user.setEmail("activation-report-it-user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Activation Report User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> get(User caller, String from, String to) {
        StringBuilder url = new StringBuilder("/api/v1/admin/analytics/activation");
        if (from != null) url.append("?from=").append(from).append("&to=").append(to);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, caller));
        return restTemplate.exchange(url.toString(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode report(User caller, String from, String to) throws Exception {
        ResponseEntity<String> response = get(caller, from, to);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private static JsonNode findWeek(JsonNode report, String weekStart) {
        for (JsonNode w : report.get("weeks")) {
            if (weekStart.equals(w.get("weekStart").asText())) return w;
        }
        return null;
    }

    private static JsonNode week(JsonNode report, String weekStart) {
        JsonNode w = findWeek(report, weekStart);
        assertThat(w).as("week " + weekStart).isNotNull();
        return w;
    }

    private static long answerCount(JsonNode report, String moment, String answer) {
        for (JsonNode a : report.get("answers")) {
            if (moment.equals(a.get("moment").asText()) && answer.equals(a.get("answer").asText())) return a.get("count").asLong();
        }
        return 0;
    }

    private static long stepAnswerCount(JsonNode report, String step, String moment, String answer) {
        for (JsonNode a : report.get("answersByStep")) {
            if (step.equals(a.get("step").asText()) && moment.equals(a.get("moment").asText())
                    && answer.equals(a.get("answer").asText())) return a.get("count").asLong();
        }
        return 0;
    }

    private static long label(JsonNode report, String field, String step, String label) {
        for (JsonNode a : report.get(field)) {
            if (step.equals(a.get("step").asText()) && label.equals(a.get("label").asText())) return a.get("count").asLong();
        }
        return 0;
    }

    private static long reasonCount(JsonNode reasons, String month, String reason) {
        for (JsonNode r : reasons) {
            if (month.equals(r.get("month").asText()) && reason.equals(r.get("reason").asText())) return r.get("count").asLong();
        }
        return 0;
    }
}
