package com.finora.imports.passwords;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.entity.Account;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementRefreshRun;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.refresh.StatementRefreshService;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementPasswordRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.StatementImportService;
import com.finora.testsupport.TestSessions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement refresh, step 4, end to end against real Postgres: a locked PDF uploaded with the
 * user's consent goes through the queue, its password follows the upload onto the statement, a
 * refresh reopens the file without asking, and every way the user or their data goes away takes
 * the password with it. Synthetic fixtures only.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-statement-password-it",
        "app.import.queue.enabled=false",
        "app.statement-passwords.save.enabled=true",
        "app.rate-limit.import-stage.max=10000"
})
class StatementPasswordIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "SYNTH9876";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountService accountService;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportJobWorker worker;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private StatementRefreshService refreshService;
    @Autowired private StatementPasswordService passwordService;
    @Autowired private StatementPasswordRepository passwordRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StatementContentService statementContentService;
    @Autowired private com.finora.imports.refresh.StatementRefreshDryRunService dryRunService;
    @Autowired private com.finora.config.BuildVersionResolver buildVersionResolver;
    @Autowired private com.finora.imports.evidence.ClosingBalanceEvidenceRederivationService rederivationService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void enableRefresh() {
        ReflectionTestUtils.setField(refreshService, "enabled", true);
    }

    @AfterEach
    void disableRefresh() {
        ReflectionTestUtils.setField(refreshService, "enabled", false);
    }

    private static byte[] lockedPdf() throws Exception {
        return PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);
    }

    private User user() {
        User user = new User();
        user.setEmail("statement-password-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Statement Password IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User user) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private ResponseEntity<String> upload(User user, byte[] pdf, String password, Boolean savePassword) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdf) {
            @Override public String getFilename() { return "statement.pdf"; }
        });
        if (password != null) body.add("password", password);
        if (savePassword != null) body.add("savePassword", savePassword.toString());
        return restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private List<ImportJob> jobsOf(User user) {
        return jobRepository.findAll().stream().filter(j -> j.getUserId().equals(user.getId())).toList();
    }

    /** Queues the locked file with consent, runs the worker and confirms what it staged. */
    private UUID importWithSavedPassword(User user, Account account) throws Exception {
        return importQueued(user, account, lockedPdf(), PASSWORD, true);
    }

    private UUID importQueued(User user, Account account, byte[] pdf, String password, Boolean save) throws Exception {
        ResponseEntity<String> accepted = upload(user, pdf, password, save);
        assertThat(accepted.getStatusCode()).as(accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(accepted).get("data").get("passwordSaved").asBoolean()).isEqualTo(Boolean.TRUE.equals(save));
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).as("last error: %s", job.getLastError()).isEqualTo(ImportJob.Status.COMPLETED);

        ImportSession session = importSessionService.getOwnedSession(user.getId(), job.getImportSessionId());
        List<ConfirmedRow> rows = new ArrayList<>();
        for (StagedRow r : importSessionService.readStagedRows(session)) {
            rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                    true, r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                    r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
        }
        return importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(), rows, account.getId(),
                null, null, null, null)).statementImportId();
    }

    /**
     * The file opened and was read. The first refresh may fill in the opening and closing balance
     * (this test confirms without echoing them, as a client would) -- facts, not rows.
     */
    private static void assertOpenedWithNoRowChanges(StatementRefreshOutcome outcome) {
        assertThat(outcome.status()).as(outcome.toString())
                .isIn(StatementRefreshRun.Status.APPLIED, StatementRefreshRun.Status.NO_CHANGES);
        assertThat(outcome.rowsChanged() + outcome.rowsAdded() + outcome.rowsRemoved()).as(outcome.toString()).isZero();
    }

    @Test
    void aLockedPdfWithConsent_isQueued_readByTheWorker_andItsPasswordMovesToTheStatement() throws Exception {
        User user = user();
        Account account = account(user);

        UUID statementId = importWithSavedPassword(user, account);

        assertThat(passwordRepository.findByStatementImportId(statementId)).as("the statement keeps it").isPresent();
        assertThat(passwordRepository.findByImportJobIdIn(jobsOf(user).stream().map(ImportJob::getId).toList()))
                .as("and the finished upload no longer holds it").isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT encrypted_password FROM statement_passwords WHERE user_id = ?",
                String.class, user.getId()))
                .as("only ciphertext is stored")
                .hasSize(1)
                .allSatisfy(stored -> assertThat(stored).doesNotContain(PASSWORD));
        assertThat(passwordService.forStatement(user.getId(), statementId)).contains(PASSWORD);
        assertThat(passwordService.forStatement(user().getId(), statementId))
                .as("another user's id finds nothing").isEmpty();
    }

    @Test
    void aRefreshReopensTheLockedStatementWithoutAsking_untilThePasswordIsRemovedInSettings() throws Exception {
        User user = user();
        UUID statementId = importWithSavedPassword(user, account(user));

        assertOpenedWithNoRowChanges(refreshService.refresh(user.getId(), statementId, null));

        ResponseEntity<String> list = restTemplate.exchange("/api/v1/statement-passwords", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        JsonNode data = read(list).get("data");
        assertThat(data.get("saveAvailable").asBoolean()).isTrue();
        assertThat(data.get("items")).hasSize(1);
        assertThat(data.get("items").get(0).get("statementImportId").asText()).isEqualTo(statementId.toString());
        assertThat(list.getBody()).as("the password is never returned").doesNotContain(PASSWORD);

        ResponseEntity<String> removed = restTemplate.exchange("/api/v1/statement-passwords/" + statementId,
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(refreshService.refresh(user.getId(), statementId, null).status())
                .as("with it removed the user is asked again")
                .isEqualTo(StatementRefreshRun.Status.NEEDS_PASSWORD);
    }

    @Test
    void anotherUserCannotSeeOrRemoveASavedPassword() throws Exception {
        User owner = user();
        UUID statementId = importWithSavedPassword(owner, account(owner));
        User other = user();

        ResponseEntity<String> list = restTemplate.exchange("/api/v1/statement-passwords", HttpMethod.GET,
                new HttpEntity<>(bearerFor(other)), String.class);
        assertThat(read(list).get("data").get("items")).isEmpty();
        ResponseEntity<String> remove = restTemplate.exchange("/api/v1/statement-passwords/" + statementId,
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(other)), String.class);
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        restTemplate.exchange("/api/v1/statement-passwords", HttpMethod.DELETE,
                new HttpEntity<>(bearerFor(other)), String.class);

        assertThat(passwordRepository.findByStatementImportId(statementId)).as("still the owner's").isPresent();
    }

    @Test
    void aWrongPasswordIsRefusedAtUpload_andNothingIsQueuedOrKept() throws Exception {
        User user = user();

        ResponseEntity<String> response = upload(user, lockedPdf(), "NOT-IT", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(response).get("errorCode").asText()).isEqualTo("IMPORT_009");
        assertThat(jobsOf(user)).isEmpty();
        assertThat(passwordRepository.findAll().stream().filter(p -> p.getUserId().equals(user.getId()))).isEmpty();
    }

    @Test
    void withoutConsent_aLockedPdfIsRefusedAsBefore_evenWithItsPassword() throws Exception {
        User user = user();

        ResponseEntity<String> response = upload(user, lockedPdf(), PASSWORD, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(response).get("errorCode").asText())
                .as("the client falls back to the path that uses the password once and keeps nothing")
                .isEqualTo("IMPORT_008");
        assertThat(jobsOf(user)).isEmpty();
    }

    @Test
    void aPasswordSentWithAnUnlockedFile_isNeverKept() throws Exception {
        User user = user();

        ResponseEntity<String> response = upload(user,
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(response).get("data").get("passwordSaved").asBoolean())
                .as("the client is told, so the user does not believe it was kept").isFalse();
        assertThat(passwordRepository.findAll().stream().filter(p -> p.getUserId().equals(user.getId()))).isEmpty();
    }

    @Test
    void deletingTheStatement_orItsAccount_deletesItsSavedPassword() throws Exception {
        User user = user();
        UUID byStatement = importWithSavedPassword(user, account(user));
        statementImportService.delete(user.getId(), byStatement, user.getId());
        assertThat(passwordRepository.findByStatementImportId(byStatement)).isEmpty();

        User other = user();
        Account account = account(other);
        UUID byAccount = importWithSavedPassword(other, account);
        accountService.delete(other.getId(), account.getId(), other.getId());
        assertThat(passwordRepository.findByStatementImportId(byAccount)).isEmpty();
    }

    @Test
    void aCancelledUploadsPassword_isSweptAway_whileAQueuedOnesStays() throws Exception {
        User user = user();
        ResponseEntity<String> accepted = upload(user, lockedPdf(), PASSWORD, true);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());

        passwordService.sweepUnusablePasswords();
        assertThat(passwordRepository.findByImportJobId(jobId)).as("still queued, still needed").isPresent();

        restTemplate.exchange("/api/v1/import/jobs/" + jobId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);
        passwordService.sweepUnusablePasswords();
        assertThat(passwordRepository.findByImportJobId(jobId)).as("cancelled: nothing will ever use it").isEmpty();
    }

    @Test
    void discardingTheStagedUpload_deletesItsPasswordAtOnce() throws Exception {
        User user = user();
        ResponseEntity<String> accepted = upload(user, lockedPdf(), PASSWORD, true);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());
        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(passwordRepository.findByImportJobId(jobId)).isPresent();

        ResponseEntity<String> discarded = restTemplate.exchange("/api/v1/import/sessions/" + job.getImportSessionId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(discarded.getStatusCode().is2xxSuccessful()).as(discarded.getBody()).isTrue();
        assertThat(passwordRepository.findByImportJobId(jobId)).isEmpty();
    }

    @Test
    void aRefreshWithConsent_savesAPasswordThatOpenedTheFile_butNeverAWrongOne() throws Exception {
        User user = user();
        UUID statementId = importWithSavedPassword(user, account(user));
        passwordService.remove(user.getId(), statementId);

        assertThat(refreshService.refresh(user.getId(), statementId, "NOT-IT", true).status())
                .isEqualTo(StatementRefreshRun.Status.NEEDS_PASSWORD);
        assertThat(passwordRepository.findByStatementImportId(statementId)).as("a wrong one is never kept").isEmpty();

        assertOpenedWithNoRowChanges(refreshService.refresh(user.getId(), statementId, PASSWORD, false));
        assertThat(passwordRepository.findByStatementImportId(statementId)).as("no consent, nothing kept").isEmpty();

        assertOpenedWithNoRowChanges(refreshService.refresh(user.getId(), statementId, PASSWORD, true));
        assertThat(passwordService.forStatement(user.getId(), statementId)).contains(PASSWORD);
    }

    @Test
    void anUploadThatBecomesSeveralStatements_givesEachOfThemThePassword() throws Exception {
        User user = user();
        byte[] composite = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildMultiSectionCompositeStatementSample(), PASSWORD);
        ResponseEntity<String> accepted = upload(user, composite, PASSWORD, true);
        assertThat(accepted.getStatusCode()).as(accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());
        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).as("last error: %s", job.getLastError()).isEqualTo(ImportJob.Status.COMPLETED);

        ImportSession session = importSessionService.getOwnedSession(user.getId(), job.getImportSessionId());
        var sections = importSessionService.readSections(session);
        assertThat(sections).as("the fixture is a two-account statement").hasSize(2);
        List<SectionConfirm> confirms = new ArrayList<>();
        for (var section : sections) {
            List<ConfirmedRow> rows = new ArrayList<>();
            for (StagedRow r : section.rows()) {
                rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                        true, r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                        r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
            }
            confirms.add(new SectionConfirm(rows, account(user).getId(), null, null, null));
        }
        var response = importService.confirmMultiSection(user.getId(), new MultiAccountConfirmRequest(session.getId(), confirms));

        assertThat(response.perAccount()).hasSize(2).allSatisfy(r ->
                assertThat(passwordService.forStatement(user.getId(), r.statementImportId())).contains(PASSWORD));
        assertThat(passwordRepository.findByImportJobId(jobId)).isEmpty();
    }

    @Test
    void aHeldUploadCanBeReadByTheReviewer_unlockedInMemory_neverStoredUnlocked() throws Exception {
        User user = user();
        ResponseEntity<String> accepted = upload(user, lockedPdf(), PASSWORD, true);
        ImportJob job = jobRepository.findById(UUID.fromString(read(accepted).get("data").get("jobId").asText())).orElseThrow();
        byte[] stored = statementContentService.read(job);
        assertThat(PdfTextExtractor.needsPassword(new ByteArrayInputStream(stored))).as("stored as uploaded").isTrue();

        StatementPasswordService.ReviewCopy copy = passwordService.reviewCopy(job, stored);

        assertThat(copy.unlocked()).isTrue();
        assertThat(PdfTextExtractor.needsPassword(new ByteArrayInputStream(copy.content())))
                .as("the reviewer's copy opens without the password").isFalse();
        assertThat(PdfTextExtractor.needsPassword(new ByteArrayInputStream(statementContentService.read(job))))
                .as("and the stored file is still the locked original").isTrue();
    }

    @Test
    void aPasswordGivenToRefreshAnUnlockedStatement_isNeverKept() throws Exception {
        User user = user();
        UUID statementId = importQueued(user, account(user),
                PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), null, null);

        assertOpenedWithNoRowChanges(refreshService.refresh(user.getId(), statementId, PASSWORD, true));

        assertThat(passwordRepository.findByStatementImportId(statementId))
                .as("the file never needed it, so there is nothing to keep").isEmpty();
    }

    @Test
    void aHeldFileThatCannotBeUnlocked_isStillHandedToTheReviewerAsStored() throws Exception {
        User user = user();
        byte[] notReallyAPdf = "%PDF-1.4\nthis is not a real document\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<String> accepted = upload(user, notReallyAPdf, null, null);
        ImportJob job = jobRepository.findById(UUID.fromString(read(accepted).get("data").get("jobId").asText())).orElseThrow();
        passwordService.saveForJob(user.getId(), job.getId(), PASSWORD);

        StatementPasswordService.ReviewCopy copy = passwordService.reviewCopy(job, notReallyAPdf);

        assertThat(copy.unlocked()).isFalse();
        assertThat(copy.content()).isEqualTo(notReallyAPdf);
    }

    @Test
    void theClosingBalanceEvidenceCheck_opensAQueuedLockedUploadWithItsSavedPassword() throws Exception {
        User user = user();
        ResponseEntity<String> accepted = upload(user, lockedPdf(), PASSWORD, true);
        UUID jobId = UUID.fromString(read(accepted).get("data").get("jobId").asText());
        worker.drainOnce();
        UUID sessionId = jobRepository.findById(jobId).orElseThrow().getImportSessionId();

        // Before the confirm, which is when ImportService runs this check. Without the saved
        // password the re-read fails with IMPORT_PDF_PASSWORD_REQUIRED and nothing is recorded.
        var evidence = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                user.getId(), sessionId, null, new BigDecimal("1000.00"));

        assertThat(evidence.assessment()).isNotNull();
    }

    @Test
    void aLockedStatementWithNoSavedPassword_isListedAsNeedingOne_andUpdatesOnceItIsSaved() throws Exception {
        User user = user();
        UUID statementId = importWithSavedPassword(user, account(user));
        passwordService.remove(user.getId(), statementId);
        // A check under a newer build, which cannot open the file without the password.
        dryRunService.check(statementId, "a-newer-build");

        JsonNode before = read(restTemplate.exchange("/api/v1/statement-refresh", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class)).get("data");
        assertThat(before.get("enabled").asBoolean()).isTrue();
        assertThat(before.get("updatable")).isEmpty();
        assertThat(before.get("needsPassword")).singleElement()
                .satisfies(p -> assertThat(p.get("statementImportId").asText()).isEqualTo(statementId.toString()));

        ResponseEntity<String> nothingToDo = restTemplate.exchange("/api/v1/statement-refresh/apply", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(read(nothingToDo).get("data").get("results")).as("a password-locked statement is left for the prompt").isEmpty();

        passwordService.saveForStatement(user.getId(), statementId, PASSWORD);
        JsonNode after = read(restTemplate.exchange("/api/v1/statement-refresh", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class)).get("data");
        assertThat(after.get("updatable")).singleElement()
                .satisfies(p -> assertThat(p.get("passwordSaved").asBoolean()).isTrue());

        JsonNode applied = read(restTemplate.exchange("/api/v1/statement-refresh/apply", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class)).get("data");
        assertThat(applied.get("results")).singleElement()
                .satisfies(r -> assertThat(r.get("status").asText()).isIn("APPLIED", "NO_CHANGES"));
        assertThat(applied.get("remaining").asInt()).isZero();
    }

    @Test
    void theAvailabilityTellsClientsTheyMayOfferToSave() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/import/jobs/availability", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user())), String.class);
        JsonNode data = read(response).get("data");
        // The queue is off in this context (the test drives the worker), so the queue is reported
        // unavailable -- and with it, saving, which only exists to let a locked file use the queue.
        assertThat(data.get("asyncImportAvailable").asBoolean()).isFalse();
        assertThat(data.get("savePasswordAvailable").asBoolean()).isFalse();
    }
}
