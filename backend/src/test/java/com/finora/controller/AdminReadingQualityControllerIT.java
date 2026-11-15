package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
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
    private final ObjectMapper mapper = new ObjectMapper();

    private User admin;
    private User owner;

    @BeforeEach
    void setUp() {
        admin = userWithOnly("PLATFORM_DIAGNOSTICS_VIEW");
        owner = createUser("USER");
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
        jdbc.update("""
                INSERT INTO statement_analysis_sessions
                    (id, reference, user_id, source, file_name, outcome, row_count, import_session_id,
                     identity_checked, bank_name, created_at)
                VALUES (?, ?, ?, ?, 'sample-statement.pdf', ?, ?, ?, TRUE, ?, ?)
                """, UUID.randomUUID(), "SA-IT-" + UUID.randomUUID().toString().substring(0, 12),
                owner.getId(), source, outcome, rows, session, bank, Timestamp.from(at));
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
        ImportJob job = new ImportJob(owner.getId(), "sample-june.pdf",
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
        assertThat(row.path("holdRate").asDouble()).isEqualTo(0.2);
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
        assertThat(bankRow(report, nine).path("holdRate").isNull()).isTrue();
        assertThat(bankRow(report, ten).path("holdRate").asDouble()).isEqualTo(0.1);
        assertThat(bankRow(report, worse).path("holdRate").asDouble()).isEqualTo(0.9);
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
