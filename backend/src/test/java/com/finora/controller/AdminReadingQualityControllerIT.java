package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.BankManagementService;
import com.finora.testsupport.TestSessions;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading quality by bank at the HTTP layer: the real permission gate, the real queries against
 * Postgres, and nothing but bank names and counts on the wire. The tables are shared with other
 * ITs, so every bank here is a made-up name unique to the test, and the "bank not known" line is
 * checked as a change from before the test's own rows.
 */
class AdminReadingQualityControllerIT extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/admin/imports/reading-quality";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BankManagementService banks;
    private final ObjectMapper mapper = new ObjectMapper();

    private User admin;
    private User owner;

    @BeforeEach
    void setUp() {
        admin = userWithOnly("PLATFORM_DIAGNOSTICS_VIEW");
        owner = customer(false);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private User createUser(String role) {
        User user = new User();
        user.setEmail("reading-quality-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reading Quality IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A customer account, internal (the founder's, a tester's) or not. */
    private User customer(boolean internal) {
        User user = new User();
        user.setEmail("reading-quality-customer-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reading Quality Customer");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user.setInternalAccount(internal);
        return userRepository.save(user);
    }

    private User userWithOnly(String permission) {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals(permission)).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("READING_QUALITY_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Role for the reading quality IT");
        role.getPermissions().add(p);
        roleRepository.save(role);
        return createUser(role.getName());
    }

    private static String bankName() {
        return "IT Bank " + UUID.randomUUID().toString().substring(0, 8);
    }

    /** One customer upload the reader parsed, for its own staging session. */
    private UUID read(String bank, int rows, Instant at) {
        UUID session = UUID.randomUUID();
        evidence(session, bank, rows, "CUSTOMER_IMPORT", "PARSED", at);
        return session;
    }

    private void evidence(UUID session, String bank, Integer rows, String source, String outcome, Instant at) {
        evidence(owner, session, bank, rows, source, outcome, null, null, at);
    }

    private void evidence(User uploader, UUID session, String bank, Integer rows, String source, String outcome,
                          String failureCode, String contentHash, Instant at) {
        jdbc.update("""
                INSERT INTO statement_analysis_sessions
                    (id, reference, user_id, source, file_name, outcome, row_count, import_session_id,
                     identity_checked, bank_name, failure_code, content_hash, created_at)
                VALUES (?, ?, ?, ?, 'sample-statement.pdf', ?, ?, ?, TRUE, ?, ?, ?, ?)
                """, UUID.randomUUID(), "SA-IT-" + UUID.randomUUID().toString().substring(0, 12),
                uploader.getId(), source, outcome, rows, session, bank, failureCode, contentHash,
                Timestamp.from(at));
    }

    /** A customer upload of the file {@code contentHash} that the reader failed on with {@code code}. */
    private void failed(String bank, String code, String contentHash) {
        evidence(owner, null, bank, null, "CUSTOMER_IMPORT", "FAILED", code, contentHash, Instant.now());
    }

    /** A statement held for trust review, staged under {@code session}. */
    private HeldStatement trustHold(UUID session) {
        ImportJob job = new ImportJob(owner.getId(), "sample-july.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(session, null, Instant.now());
        importJobRepository.save(job);
        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                "HLD-IT-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(session, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    /** An import held because the reader failed, never naming the bank. */
    private ImportJob importHold() {
        return importHold(owner);
    }

    private ImportJob importHold(User uploader) {
        ImportJob job = new ImportJob(uploader.getId(), "sample-june.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row found",
                "IllegalStateException", ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private void claudeFix(UUID jobId, String state, Instant at) {
        jdbc.update("""
                INSERT INTO claude_fix_jobs (job_id, state, attempt, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?)
                """, jobId, state, Timestamp.from(at), Timestamp.from(at));
    }

    private void engineerFix(HeldStatement held, String fixReference, Instant at) {
        jdbc.update("UPDATE held_statements SET fix_reference = ? WHERE id = ?", fixReference, held.getId());
        jdbc.update("""
                INSERT INTO held_statement_events (held_statement_id, event_type, created_at)
                VALUES (?, 'FINDINGS_UPDATED', ?)
                """, held.getId(), Timestamp.from(at));
    }

    /** A made-up custom bank, so no other test's rows share its name. Returns its official name. */
    private String customBank(String id) {
        String name = "IT Profile Bank " + id;
        banks.createCustom(admin.getId(), new AccountDto.BankDto.CreateRequest(
                id, name, "ITPB", null, null, null, null, null));
        return name;
    }

    private static String customBankId() {
        return "ITPB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /** A layout whose profile carries {@code bankId}, linked by an admin ({@code MANUAL}) or grouped
     *  automatically ({@code AUTO}). Returns the layout's fingerprint. */
    private String layoutInProfile(String bankId, String linkSource) {
        String fingerprint = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        UUID profile = UUID.randomUUID();
        jdbc.update("INSERT INTO layout_profile (id, name, bank_id, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
                profile, "IT profile " + fingerprint, bankId);
        jdbc.update("""
                INSERT INTO layout_registry (id, fingerprint, first_seen, last_seen, profile_id, profile_version,
                                             profile_link_source)
                VALUES (?, ?, now(), now(), ?, 1, ?)
                """, UUID.randomUUID(), fingerprint, profile, linkSource);
        return fingerprint;
    }

    /** A customer upload of layout {@code fingerprint}; {@code bank} is what the file itself named. */
    private UUID upload(User uploader, String fingerprint, String bank, boolean identityChecked, String outcome,
                        String failureCode) {
        UUID session = "PARSED".equals(outcome) ? UUID.randomUUID() : null;
        jdbc.update("""
                INSERT INTO statement_analysis_sessions
                    (id, reference, user_id, source, outcome, row_count, import_session_id, layout_fingerprint,
                     identity_checked, bank_name, failure_code, content_hash)
                VALUES (?, ?, ?, 'CUSTOMER_IMPORT', ?, 5, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "SA-IT-" + UUID.randomUUID().toString().substring(0, 12),
                uploader.getId(), outcome, session, fingerprint, identityChecked, bank, failureCode,
                "hash-" + UUID.randomUUID());
        return session;
    }

    private ResponseEntity<String> get(User user, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return restTemplate.exchange(PATH + query, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode report(String query) throws Exception {
        ResponseEntity<String> response = get(admin, query);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).path("data");
    }

    private JsonNode report() throws Exception {
        return report("");
    }

    private static JsonNode bankRow(JsonNode report, String bank) {
        for (JsonNode row : report.path("banks")) {
            if (row.path("bank").asText().equals(bank)) return row;
        }
        return null;
    }

    private static int indexOf(JsonNode report, String bank) {
        int i = 0;
        for (JsonNode row : report.path("banks")) {
            if (row.path("bank").asText().equals(bank)) return i;
            i++;
        }
        return -1;
    }

    private static String weekOf(Instant at) {
        return at.atZone(UserZone.DEFAULT).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    private static JsonNode weekRow(JsonNode bankRow, String weekStart) {
        for (JsonNode week : bankRow.path("weeks")) {
            if (week.path("weekStart").asText().equals(weekStart)) return week;
        }
        return null;
    }

    // ---- tests ---------------------------------------------------------------------------------

    @Test
    void aBank_countsStatementsRowsHoldsAndItsHoldRate() throws Exception {
        String bank = bankName();
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) sessions.add(read(bank, 5, Instant.now()));
        trustHold(sessions.get(0));
        trustHold(sessions.get(1));

        JsonNode row = bankRow(report(), bank);

        assertThat(row).isNotNull();
        assertThat(row.path("statementsRead").asLong()).isEqualTo(10);
        assertThat(row.path("rowsRead").asLong()).isEqualTo(50);
        assertThat(row.path("holds").asLong()).isEqualTo(2);
        assertThat(row.path("problemRate").asDouble()).isEqualTo(0.2);
        assertThat(row.path("weeks")).hasSize(12);
        JsonNode thisWeek = weekRow(row, weekOf(Instant.now()));
        assertThat(thisWeek.path("statementsRead").asLong()).isEqualTo(10);
        assertThat(thisWeek.path("holds").asLong()).isEqualTo(2);
    }

    @Test
    void onlyCustomerUploadsTheReaderParsedCountAsRead() throws Exception {
        String bank = bankName();
        read(bank, 5, Instant.now());
        evidence(UUID.randomUUID(), bank, 7, "ADMIN_ANALYSIS", "PARSED", Instant.now());
        evidence(UUID.randomUUID(), bank, null, "CUSTOMER_IMPORT", "FAILED", Instant.now());

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("statementsRead").asLong()).isEqualTo(1);
        assertThat(row.path("rowsRead").asLong()).isEqualTo(5);
    }

    @Test
    void belowTenStatements_noRateAndSortedAfterEveryBankWithOne() throws Exception {
        String nine = bankName();
        String ten = bankName();
        String worse = bankName();
        List<UUID> nineSessions = new ArrayList<>();
        List<UUID> tenSessions = new ArrayList<>();
        List<UUID> worseSessions = new ArrayList<>();
        for (int i = 0; i < 9; i++) nineSessions.add(read(nine, 1, Instant.now()));
        for (int i = 0; i < 10; i++) tenSessions.add(read(ten, 1, Instant.now()));
        for (int i = 0; i < 10; i++) worseSessions.add(read(worse, 1, Instant.now()));
        trustHold(nineSessions.get(0));
        trustHold(nineSessions.get(1));
        trustHold(nineSessions.get(2));
        trustHold(tenSessions.get(0));
        for (int i = 0; i < 9; i++) trustHold(worseSessions.get(i));

        JsonNode report = report();

        assertThat(report.path("minStatements").asInt()).isEqualTo(10);
        assertThat(bankRow(report, nine).path("problemRate").isNull()).isTrue();
        assertThat(bankRow(report, ten).path("problemRate").asDouble()).isEqualTo(0.1);
        assertThat(bankRow(report, worse).path("problemRate").asDouble()).isEqualTo(0.9);
        assertThat(indexOf(report, worse)).isLessThan(indexOf(report, ten));
        assertThat(indexOf(report, ten)).isLessThan(indexOf(report, nine));
    }

    @Test
    void aHoldTakesItsBankFromTheLatestUploadRecordNamingOne_notFromTheHoldsOwnBankName() throws Exception {
        String older = bankName();
        String newer = bankName();
        UUID session = UUID.randomUUID();
        evidence(session, older, 3, "CUSTOMER_IMPORT", "PARSED", Instant.now().minus(Duration.ofHours(2)));
        evidence(session, newer, 3, "CUSTOMER_IMPORT", "PARSED", Instant.now().minus(Duration.ofHours(1)));
        evidence(session, null, 3, "CUSTOMER_IMPORT", "PARSED", Instant.now());
        HeldStatement held = trustHold(session);
        String holdsOwnName = bankName();
        jdbc.update("UPDATE held_statements SET bank_name = ? WHERE id = ?", holdsOwnName, held.getId());

        JsonNode report = report();

        assertThat(bankRow(report, newer).path("holds").asLong()).isEqualTo(1);
        assertThat(bankRow(report, older).path("holds").asLong()).isZero();
        assertThat(bankRow(report, holdsOwnName)).isNull();
    }

    /**
     * A re-read of a held statement (an admin's "re-run parser", or the Claude fix's re-read after
     * its PR deploys -- both {@code HeldStatementService.rerunParser}) stages the new read under a
     * new session and moves the job to it ({@code ImportJob.replaceHeldSession}). That session has
     * no upload record, so the hold and its fix keep the bank through the hold's own name.
     */
    @Test
    void aHoldReReadUnderANewSession_keepsItsBankAndItsFix() throws Exception {
        String bank = bankName();
        HeldStatement held = trustHold(read(bank, 3, Instant.now()));
        jdbc.update("UPDATE held_statements SET bank_name = ? WHERE id = ?", bank, held.getId());
        claudeFix(held.getImportJobId(), "FIXED", Instant.now());
        jdbc.update("UPDATE import_jobs SET import_session_id = ? WHERE id = ?", UUID.randomUUID(), held.getImportJobId());

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("holds").asLong()).isEqualTo(1);
        assertThat(row.path("fixes").asLong()).isEqualTo(1);
    }

    @Test
    void aReReadHoldWhoseOwnBankNameNoUploadRecordUses_staysBankNotKnown() throws Exception {
        JsonNode before = report().path("bankNotKnown");
        String generic = "IT Generic " + UUID.randomUUID().toString().substring(0, 8);
        HeldStatement held = trustHold(read(bankName(), 3, Instant.now()));
        jdbc.update("UPDATE held_statements SET bank_name = ? WHERE id = ?", generic, held.getId());
        jdbc.update("UPDATE import_jobs SET import_session_id = ? WHERE id = ?", UUID.randomUUID(), held.getImportJobId());

        JsonNode report = report();

        assertThat(bankRow(report, generic)).isNull();
        assertThat(report.path("bankNotKnown").path("trustHolds").asLong()
                - before.path("trustHolds").asLong()).isEqualTo(1);
    }

    /** The upload record exists and the engine named no bank: its read is not counted under any
     *  bank, so neither is its hold, whatever name the hold itself carries. */
    @Test
    void anUploadTheEngineNamedNoBankForAndNoProfileNames_keepsItsHoldInBankNotKnown() throws Exception {
        String known = bankName();
        read(known, 1, Instant.now());
        UUID session = UUID.randomUUID();
        evidence(session, null, 3, "CUSTOMER_IMPORT", "PARSED", Instant.now());
        JsonNode before = report().path("bankNotKnown");
        HeldStatement held = trustHold(session);
        jdbc.update("UPDATE held_statements SET bank_name = ? WHERE id = ?", known, held.getId());

        JsonNode report = report();

        assertThat(bankRow(report, known).path("holds").asLong()).isZero();
        assertThat(report.path("bankNotKnown").path("trustHolds").asLong()
                - before.path("trustHolds").asLong()).isEqualTo(1);
    }

    @Test
    void holdsWithNoBank_goToBankNotKnown() throws Exception {
        JsonNode before = report().path("bankNotKnown");

        importHold();
        trustHold(UUID.randomUUID()); // its session has no upload record
        trustHold(null);

        JsonNode after = report().path("bankNotKnown");
        assertThat(after.path("importHolds").asLong() - before.path("importHolds").asLong()).isEqualTo(1);
        assertThat(after.path("trustHolds").asLong() - before.path("trustHolds").asLong()).isEqualTo(2);
    }

    @Test
    void anImportHoldLaterReadUnderABank_countsForThatBankOnce() throws Exception {
        String bank = bankName();
        UUID session = read(bank, 4, Instant.now());
        ImportJob job = importHold();
        jdbc.update("UPDATE import_jobs SET import_session_id = ? WHERE id = ?", session, job.getId());
        // And the same job, re-read, was then held for trust review too: still one statement held.
        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                "HLD-IT-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        assertThat(held.getId()).isNotNull();

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("holds").asLong()).isEqualTo(1);
    }

    @Test
    void aFix_isCountedOncePerHoldAndDatedByTheEarlierOfClaudeAndTheEngineer() throws Exception {
        String bank = bankName();
        HeldStatement both = trustHold(read(bank, 1, Instant.now()));
        Instant engineerAt = Instant.now().minus(Duration.ofDays(8));
        claudeFix(both.getImportJobId(), "FIXED", Instant.now());
        engineerFix(both, "#2468", engineerAt);

        HeldStatement claudeOnly = trustHold(read(bank, 1, Instant.now()));
        claudeFix(claudeOnly.getImportJobId(), "FIXED", Instant.now());

        HeldStatement stillOpen = trustHold(read(bank, 1, Instant.now()));
        claudeFix(stillOpen.getImportJobId(), "PR_OPEN", Instant.now());

        HeldStatement blankReference = trustHold(read(bank, 1, Instant.now()));
        engineerFix(blankReference, "   ", Instant.now());

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("fixes").asLong()).isEqualTo(2);
        assertThat(weekRow(row, weekOf(engineerAt)).path("fixes").asLong())
                .isEqualTo(weekOf(engineerAt).equals(weekOf(Instant.now())) ? 2 : 1);
    }

    @Test
    void theEngineersLatestFindingsDatesTheirFix() throws Exception {
        String bank = bankName();
        HeldStatement held = trustHold(read(bank, 1, Instant.now()));
        engineerFix(held, "#100", Instant.now().minus(Duration.ofDays(200)));
        engineerFix(held, "#100", Instant.now());

        assertThat(bankRow(report(), bank).path("fixes").asLong()).isEqualTo(1);
    }

    @Test
    void theFourWeekPeriod_leavesOutOlderWeeks() throws Exception {
        String bank = bankName();
        read(bank, 2, Instant.now());
        read(bank, 2, Instant.now().minus(Duration.ofDays(40)));
        HeldStatement held = trustHold(read(bank, 2, Instant.now()));
        engineerFix(held, "#7", Instant.now().minus(Duration.ofDays(40)));

        JsonNode four = report("?weeks=4");
        JsonNode twelve = report("?weeks=12");

        assertThat(four.path("weekStarts")).hasSize(4);
        assertThat(four.path("weekStarts").get(3).asText()).isEqualTo(weekOf(Instant.now()));
        assertThat(LocalDate.parse(four.path("weekStarts").get(0).asText()).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(bankRow(four, bank).path("statementsRead").asLong()).isEqualTo(2);
        assertThat(bankRow(four, bank).path("fixes").asLong()).isZero();
        assertThat(bankRow(twelve, bank).path("statementsRead").asLong()).isEqualTo(3);
        assertThat(bankRow(twelve, bank).path("fixes").asLong()).isEqualTo(1);
    }

    @Test
    void readerFailures_countOncePerFileAndJoinTheRate() throws Exception {
        String bank = bankName();
        for (int i = 0; i < 8; i++) read(bank, 1, Instant.now());
        String retried = "hash-" + UUID.randomUUID();
        failed(bank, "IMPORT_NO_HEADER_DETECTED", retried);
        failed(bank, "IMPORT_NO_HEADER_DETECTED", retried);           // automatic retry: same file
        failed(bank, "IllegalStateException", "hash-" + UUID.randomUUID()); // unexpected exception
        failed(bank, "IMPORT_SCANNED_OCR_REQUIRED", null);            // no hash: counts alone
        failed(bank, "IMPORT_PDF_PASSWORD_INVALID", "hash-" + UUID.randomUUID());   // the customer's
        failed(bank, "IMPORT_CORRUPT_PDF", "hash-" + UUID.randomUUID());            // the file's
        failed(bank, "IMPORT_NO_ACTIVITY_IN_PERIOD", "hash-" + UUID.randomUUID());  // nothing to read
        failed(bank, "IMPORT_SYSTEM_BUSY", "hash-" + UUID.randomUUID());            // the server's

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("statementsRead").asLong()).isEqualTo(8);
        assertThat(row.path("failedReads").asLong()).isEqualTo(3);
        assertThat(row.path("holds").asLong()).isZero();
        assertThat(row.path("problemRate").asDouble()).isEqualTo(3.0 / 11);
        assertThat(weekRow(row, weekOf(Instant.now())).path("failedReads").asLong()).isEqualTo(3);
    }

    @Test
    void tenTried_countsFailuresTowardTheMinimum() throws Exception {
        String bank = bankName();
        for (int i = 0; i < 9; i++) read(bank, 1, Instant.now());
        failed(bank, "IMPORT_NO_TRANSACTIONS_FOUND", "hash-" + UUID.randomUUID());

        assertThat(bankRow(report(), bank).path("problemRate").asDouble()).isEqualTo(0.1);
    }

    @Test
    void anImportHoldWhoseFailureNamesABank_isThatBanksFailedReadOnce_andTakesItsFix() throws Exception {
        String bank = bankName();
        JsonNode before = report().path("bankNotKnown");
        ImportJob job = importHold();
        failed(bank, "IllegalStateException", job.getContentHash());
        failed(bank, "IllegalStateException", job.getContentHash()); // the retry before it dead-lettered
        claudeFix(job.getId(), "FIXED", Instant.now());

        JsonNode report = report();
        JsonNode row = bankRow(report, bank);

        assertThat(row.path("failedReads").asLong()).isEqualTo(1);
        assertThat(row.path("holds").asLong()).isZero();
        assertThat(row.path("fixes").asLong()).isEqualTo(1);
        assertThat(report.path("bankNotKnown").path("importHolds").asLong())
                .isEqualTo(before.path("importHolds").asLong());
    }

    @Test
    void anotherCustomersFailureOfTheSameFile_doesNotNameAnImportHoldsBank() throws Exception {
        String bank = bankName();
        JsonNode before = report().path("bankNotKnown");
        ImportJob job = importHold();
        evidence(customer(false), null, bank, null, "CUSTOMER_IMPORT", "FAILED", "IllegalStateException",
                job.getContentHash(), Instant.now());

        JsonNode report = report();

        assertThat(bankRow(report, bank).path("holds").asLong()).isZero();
        assertThat(report.path("bankNotKnown").path("importHolds").asLong()
                - before.path("importHolds").asLong()).isEqualTo(1);
    }

    @Test
    void internalAndAdminAccounts_areLeftOut() throws Exception {
        String bank = bankName();
        read(bank, 4, Instant.now());
        for (User other : List.of(customer(true), createUser("USER"))) {
            UUID session = UUID.randomUUID();
            evidence(other, session, bank, 50, "CUSTOMER_IMPORT", "PARSED", null, null, Instant.now());
            evidence(other, null, bank, null, "CUSTOMER_IMPORT", "FAILED", "IMPORT_NO_HEADER_DETECTED",
                    "hash-" + UUID.randomUUID(), Instant.now());
            ImportJob job = new ImportJob(other.getId(), "sample-july.pdf",
                    "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
            job.markClaimed("worker", Instant.now());
            job.holdForTrustReview(session, null, Instant.now());
            importJobRepository.save(job);
            heldStatementRepository.save(new HeldStatement(
                    "HLD-IT-" + UUID.randomUUID().toString().substring(0, 8), job.getId(), other.getId(),
                    job.getObjectKey(), "Printed and parsed transaction count disagree (ROW_GROUPING)"));
            claudeFix(job.getId(), "FIXED", Instant.now());
        }

        JsonNode row = bankRow(report(), bank);

        assertThat(row.path("statementsRead").asLong()).isEqualTo(1);
        assertThat(row.path("rowsRead").asLong()).isEqualTo(4);
        assertThat(row.path("failedReads").asLong()).isZero();
        assertThat(row.path("holds").asLong()).isZero();
        assertThat(row.path("fixes").asLong()).isZero();
    }

    @Test
    void aDeletedCustomersUploadRecord_stillCounts() throws Exception {
        String bank = bankName();
        UUID gone = UUID.randomUUID(); // no users row: the account was deleted, the record stays
        jdbc.update("""
                INSERT INTO statement_analysis_sessions
                    (id, reference, user_id, source, outcome, row_count, identity_checked, bank_name)
                VALUES (?, ?, ?, 'CUSTOMER_IMPORT', 'PARSED', 6, TRUE, ?)
                """, UUID.randomUUID(), "SA-IT-" + UUID.randomUUID().toString().substring(0, 12), gone, bank);

        assertThat(bankRow(report(), bank).path("statementsRead").asLong()).isEqualTo(1);
    }

    @Test
    void anAdminAttachedProfile_namesTheReadsFailuresAndHoldsOfFilesThatNamedNoBank() throws Exception {
        String id = customBankId();
        String bank = customBank(id);
        String fingerprint = layoutInProfile(id, "MANUAL");
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < 9; i++) sessions.add(upload(owner, fingerprint, null, true, "PARSED", null));
        upload(owner, fingerprint, null, true, "FAILED", "IMPORT_NO_TRANSACTIONS_FOUND");
        upload(owner, fingerprint, null, true, "FAILED", "IMPORT_PDF_PASSWORD_INVALID"); // the customer's
        upload(owner, fingerprint, null, false, "PARSED", null); // before V239: never named this way
        HeldStatement held = trustHold(sessions.get(0));
        claudeFix(held.getImportJobId(), "FIXED", Instant.now());

        JsonNode row = bankRow(report(), bank);

        assertThat(row).isNotNull();
        assertThat(row.path("statementsRead").asLong()).isEqualTo(9);
        assertThat(row.path("rowsRead").asLong()).isEqualTo(45);
        assertThat(row.path("failedReads").asLong()).isEqualTo(1);
        assertThat(row.path("holds").asLong()).isEqualTo(1);
        assertThat(row.path("fixes").asLong()).isEqualTo(1);
        assertThat(row.path("problemRate").asDouble()).isEqualTo(0.2);
    }

    @Test
    void aFileThatNamedItsOwnBank_keepsItEvenInAProfileOfAnother() throws Exception {
        String id = customBankId();
        String profileBank = customBank(id);
        String fileBank = bankName();
        String fingerprint = layoutInProfile(id, "MANUAL");
        upload(owner, fingerprint, fileBank, true, "PARSED", null);

        JsonNode report = report();

        assertThat(bankRow(report, fileBank).path("statementsRead").asLong()).isEqualTo(1);
        assertThat(bankRow(report, profileBank)).isNull();
    }

    @Test
    void anAutomaticallyGroupedProfile_namesFilesOnlyOnceTwoUploadersFilesAgree() throws Exception {
        String id = customBankId();
        String bank = customBank(id);
        String fingerprint = layoutInProfile(id, "AUTO");
        upload(owner, fingerprint, null, true, "PARSED", null);
        upload(owner, fingerprint, bank, true, "PARSED", null);

        JsonNode oneUploader = bankRow(report(), bank);

        upload(customer(false), fingerprint, bank, true, "PARSED", null);
        JsonNode twoUploaders = bankRow(report(), bank);

        assertThat(oneUploader.path("statementsRead").asLong()).isEqualTo(1);
        assertThat(twoUploaders.path("statementsRead").asLong()).isEqualTo(3);
    }

    @Test
    void anyOtherPeriod_isRejected() throws Exception {
        ResponseEntity<String> response = get(admin, "?weeks=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutDiagnosticsPermission_isForbidden() {
        ResponseEntity<String> response = get(userWithOnly("TRUST_REVIEW_MANAGE"), "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theResponseCarriesBankNamesAndCountsOnly() throws Exception {
        String bank = bankName();
        UUID session = read(bank, 1, Instant.now());
        HeldStatement held = trustHold(session);

        String body = get(admin, "").getBody();

        assertThat(body).contains(bank);
        assertThat(body).doesNotContain("sample-statement.pdf", "sample-july.pdf",
                owner.getId().toString(), held.getImportJobId().toString(), held.getHeldId(),
                session.toString(), owner.getEmail());
    }
}
