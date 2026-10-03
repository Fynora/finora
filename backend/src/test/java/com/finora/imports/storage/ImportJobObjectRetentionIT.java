package com.finora.imports.storage;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.StatementUpload;
import com.finora.imports.jobs.ImportJobService;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AccountPurgeSweepService;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object an asynchronous upload writes, followed through every way its references end.
 *
 * <p>{@link StatementStorageSweepServiceIT} builds its import_jobs fixtures with the SAME object key
 * as a statement_imports row. Production never produces that pairing: {@code ImportJobService.accept}
 * stores the upload itself (streaming AES-GCM, fresh IV, uncompressed), and confirming the staged
 * session later stores the bytes again through {@code StatementContentService.store} (gzip, then
 * encrypt with another fresh IV). Keys are the SHA-256 of what was stored, so the two keys always
 * differ even though both rows carry the same {@code content_hash}. The job's object therefore
 * never shows up through {@code StatementImportRepository.findObjectsUnreferencedSince}, the
 * statement-side discovery query. This class drives the real upload, worker and confirm paths so
 * the keys are the ones production would write, not ones a fixture chose.
 *
 * <p>Committed data, not {@code @Transactional}: the upload, the worker and the confirm each run in
 * their own transactions. {@link #neutraliseBackdates} puts every backdated timestamp this class
 * wrote back to now, so a later class's sweep -- which reads the whole shared database -- never
 * meets one of these rows as an old candidate.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-job-object-retention-it",
        "app.import.queue.enabled=false"
})
class ImportJobObjectRetentionIT extends AbstractIntegrationTest {

    @Autowired private ImportJobService importJobService;
    @Autowired private ImportJobWorker worker;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountPurgeSweepService accountPurgeSweepService;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementStorage storage;
    @Autowired private JdbcTemplate jdbcTemplate;

    private StatementStorageSweepService sweepService;
    private final List<UUID> usersCreated = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Its own instance over the context's real storage, as StatementStorageSweepServiceIT does,
        // so the batch can be raised: discovery reads the whole shared database, oldest first, and a
        // stock batch of 200 could be filled by other classes' rows before reaching these.
        sweepService = new StatementStorageSweepService(Optional.of(storage), statementImportRepository,
                importSessionRepository, importJobRepository);
        ReflectionTestUtils.setField(sweepService, "retentionDays", 90);
        ReflectionTestUtils.setField(sweepService, "batchSize", 100_000);
        ReflectionTestUtils.setField(sweepService, "sweepEnabled", true);
    }

    @AfterEach
    void neutraliseBackdates() {
        for (UUID userId : usersCreated) {
            jdbcTemplate.update("UPDATE statement_imports SET deleted_at = now() WHERE user_id = ? AND deleted_at IS NOT NULL", userId);
            jdbcTemplate.update("UPDATE import_jobs SET finished_at = now() WHERE user_id = ? AND finished_at IS NOT NULL", userId);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record Owner(UUID userId, UUID accountId) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("job-object-retention-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Job Object Retention IT User");
        user.setPhoneVerified(true);
        UUID userId = userRepository.save(user).getId();
        usersCreated.add(userId);

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return new Owner(userId, accountRepository.save(account).getId());
    }

    /** Unique per call, so no two uploads in this class -- or across the shared database -- share a
     *  content hash and meet each other's dedup or reference checks. */
    private static byte[] csv() {
        return ("""
                Date,Description,Amount,Type
                2026-07-10,SWIGGY ORDER %s,486.00,DEBIT
                2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
                """.formatted(UUID.randomUUID())).getBytes(StandardCharsets.UTF_8);
    }

    private ImportJob accept(Owner owner, byte[] content) throws Exception {
        return importJobService.accept(owner.userId(),
                new MockMultipartFile("file", "statement.csv", "text/csv", content), StatementUpload.Format.CSV);
    }

    private ImportJob staged(Owner owner, byte[] content) throws Exception {
        ImportJob accepted = accept(owner, content);
        worker.drainOnce();
        ImportJob job = importJobRepository.findById(accepted.getId()).orElseThrow();
        assertThat(job.getStatus()).as("last error: %s", job.getLastError()).isEqualTo(ImportJob.Status.COMPLETED);
        return job;
    }

    /** Confirms what the worker staged, the way the confirm endpoint does. */
    private StatementImport confirm(Owner owner, ImportJob job) {
        ImportSession session = importSessionService.getOwnedSession(owner.userId(), job.getImportSessionId());
        List<ConfirmedRow> rows = new ArrayList<>();
        for (StagedRow r : importSessionService.readStagedRows(session)) {
            rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                    true, r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                    r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
        }
        UUID statementId = importService.confirmSession(owner.userId(), new ConfirmRequest(session.getId(), rows,
                owner.accountId(), null, null, null, null)).statementImportId();
        return statementImportRepository.findById(statementId).orElseThrow();
    }

    private ContentAddress addressOf(ImportJob job) {
        return new ContentAddress(job.getContentHash(), job.getObjectKey());
    }

    private ContentAddress addressOf(StatementImport statement) {
        return new ContentAddress(statement.getContentHash(), statement.getObjectKey());
    }

    private void backdateFinished(ImportJob job, Instant finishedAt) {
        jdbcTemplate.update("UPDATE import_jobs SET finished_at = ? WHERE id = ?", Timestamp.from(finishedAt), job.getId());
    }

    private void backdateStatementDeletion(StatementImport statement, Instant deletedAt) {
        jdbcTemplate.update("UPDATE statement_imports SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), statement.getId());
    }

    /** Every row in the database that names this key, in any table, in any state -- soft-deleted
     *  statements and terminal jobs included. Zero means nothing can ever reach the object again. */
    private int rowsNaming(String objectKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM statement_imports WHERE object_key = ?)
                     + (SELECT count(*) FROM import_sessions WHERE object_key = ?)
                     + (SELECT count(*) FROM import_jobs WHERE object_key = ?)
                     + (SELECT count(*) FROM held_statements WHERE statement_object_key = ?)
                """, Integer.class, objectKey, objectKey, objectKey, objectKey);
        return count == null ? 0 : count;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    // ------------------------------------------------------------------ the premise

    /**
     * What the rest of this class rests on: identical bytes, two objects. The job's key is never a
     * statement's key, so the statement-side discovery query cannot return it however far its
     * cutoff is pushed.
     */
    @Test
    void anAsyncUploadAndTheStatementItBecomesAreTwoDifferentObjects() throws Exception {
        Owner owner = owner();
        ImportJob job = staged(owner, csv());
        StatementImport statement = confirm(owner, job);

        assertThat(statement.getContentHash()).isEqualTo(job.getContentHash());
        assertThat(statement.getObjectKey()).isNotEqualTo(job.getObjectKey());
        assertThat(storage.exists(addressOf(job))).isTrue();
        assertThat(storage.exists(addressOf(statement))).isTrue();

        statementImportService.delete(owner.userId(), statement.getId(), owner.userId());
        List<Object[]> everyStatementCandidate =
                statementImportRepository.findObjectsUnreferencedSince(Instant.now().plus(3650, ChronoUnit.DAYS), 100_000);
        assertThat(everyStatementCandidate).extracting(row -> (String) row[1])
                .contains(statement.getObjectKey())
                .doesNotContain(job.getObjectKey());
    }

    // ------------------------------------------------------------------ COMPLETED

    /**
     * The user deleted the statement the upload became, the staged session is long gone, and the
     * retention window has passed for both. Both copies must go.
     */
    @Test
    void completedJob_whoseStatementWasDeleted_bothCopiesReclaimedOnceTheWindowPasses() throws Exception {
        Owner owner = owner();
        ImportJob job = staged(owner, csv());
        StatementImport statement = confirm(owner, job);
        // The 48h session TTL, done for this row only -- sweepExpiredSessions would reach every
        // other class's sessions too.
        importSessionRepository.deleteById(job.getImportSessionId());
        statementImportService.delete(owner.userId(), statement.getId(), owner.userId());
        backdateStatementDeletion(statement, daysAgo(91));
        backdateFinished(job, daysAgo(91));

        sweepService.sweep();

        assertThat(storage.exists(addressOf(statement))).as("the statement's own copy").isFalse();
        assertThat(storage.exists(addressOf(job)))
                .as("the upload's copy: no live row needs it, and the window has passed")
                .isFalse();
    }

    /**
     * The job's copy is a duplicate of a statement that is still live. Reclaiming it must not touch
     * the statement's object -- the user can still download what they imported.
     */
    @Test
    void completedJob_withItsStatementStillLive_onlyTheUploadsCopyIsReclaimed() throws Exception {
        Owner owner = owner();
        byte[] content = csv();
        ImportJob job = staged(owner, content);
        StatementImport statement = confirm(owner, job);
        backdateFinished(job, daysAgo(91));

        sweepService.sweep();

        assertThat(storage.exists(addressOf(job))).isFalse();
        assertThat(storage.exists(addressOf(statement))).isTrue();
        assertThat(statementImportService.getFile(owner.userId(), statement.getId()).content()).isEqualTo(content);
    }

    /**
     * A job is considered once. Its object is gone after the first run; without the release
     * marker the job would come back as a candidate on every later run, oldest first, and a
     * backlog of them would fill the batch ahead of anything new.
     */
    @Test
    void releasedJob_isNeverACandidateAgain() throws Exception {
        Owner owner = owner();
        ImportJob job = staged(owner, csv());
        backdateFinished(job, daysAgo(91));

        sweepService.sweep();

        assertThat(importJobRepository.findById(job.getId()).orElseThrow().getObjectReleasedAt()).isNotNull();
        assertThat(importJobRepository.findReleasableObjects(Instant.now(), 100_000))
                .extracting(row -> (UUID) row[0])
                .doesNotContain(job.getId());
    }

    /**
     * Another live row naming the same key keeps the object, and the job is released anyway -- that
     * row's own lifecycle decides the object from here. No production path writes a job key a live
     * statement shares today; the key is copied across directly so the re-check has something to
     * find.
     */
    @Test
    void completedJob_whoseKeyALiveStatementAlsoNames_keepsTheObjectAndIsReleased() throws Exception {
        Owner owner = owner();
        ImportJob job = staged(owner, csv());
        StatementImport statement = confirm(owner, job);
        jdbcTemplate.update("UPDATE import_jobs SET object_key = ? WHERE id = ?", statement.getObjectKey(), job.getId());
        backdateFinished(job, daysAgo(91));

        StatementStorageSweepService.Result result = sweepService.sweep();

        assertThat(result.skipped()).isGreaterThanOrEqualTo(1);
        assertThat(storage.exists(addressOf(statement))).isTrue();
        assertThat(importJobRepository.findById(job.getId()).orElseThrow().getObjectReleasedAt()).isNotNull();
    }

    @Test
    void completedJob_insideTheWindow_isKept() throws Exception {
        Owner owner = owner();
        ImportJob job = staged(owner, csv());
        backdateFinished(job, daysAgo(10));

        sweepService.sweep();

        assertThat(storage.exists(addressOf(job))).isTrue();
    }

    // ------------------------------------------------------------------ CANCELLED

    @Test
    void cancelledJob_isReclaimedOnceTheWindowPasses() throws Exception {
        Owner owner = owner();
        ImportJob job = accept(owner, csv());
        importJobService.cancel(owner.userId(), job.getId());
        backdateFinished(job, daysAgo(91));
        assertThat(rowsNaming(job.getObjectKey())).as("only the cancelled job names it").isEqualTo(1);

        sweepService.sweep();

        assertThat(storage.exists(addressOf(job))).isFalse();
    }

    // ------------------------------------------------------------------ statuses that keep their copy

    /**
     * FAILED, HELD_FOR_REVIEW and HELD_FOR_TRUST_REVIEW keep their object however old: retry
     * without re-upload, and the reviewer's only copy. See {@code
     * StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES}. The status is written directly --
     * the rule under test is the sweep's, not how a job reaches the status.
     */
    @Test
    void failedAndHeldJobs_keepTheirObjectHoweverOld() throws Exception {
        Owner owner = owner();
        List<ImportJob> kept = new ArrayList<>();
        for (ImportJob.Status status : List.of(ImportJob.Status.FAILED, ImportJob.Status.HELD_FOR_REVIEW,
                ImportJob.Status.HELD_FOR_TRUST_REVIEW)) {
            ImportJob job = accept(owner, csv());
            jdbcTemplate.update("UPDATE import_jobs SET status = ?, finished_at = ? WHERE id = ?",
                    status.name(), Timestamp.from(daysAgo(400)), job.getId());
            kept.add(job);
        }

        sweepService.sweep();

        for (ImportJob job : kept) {
            assertThat(storage.exists(addressOf(job))).as("job %s", job.getId()).isTrue();
        }
    }

    // ------------------------------------------------------------------ account purge

    /**
     * Account purge hard-deletes every import_jobs row. Whatever object those rows named has to be
     * reclaimed then -- afterwards no row anywhere names it, so no later sweep can find it. FAILED
     * included: its protection is for the user's retry, and the user is gone.
     */
    @Test
    void accountPurge_reclaimsEveryObjectItsImportJobsNamed() throws Exception {
        Owner owner = owner();
        ImportJob completed = staged(owner, csv());
        StatementImport statement = confirm(owner, completed);
        ImportJob failed = accept(owner, csv());
        jdbcTemplate.update("UPDATE import_jobs SET status = 'FAILED', finished_at = now() WHERE id = ?", failed.getId());

        accountPurgeSweepService.adminPurge(owner.userId(), UUID.randomUUID());

        assertThat(importJobRepository.findByUserIdOrderByCreatedAtDesc(owner.userId(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
        assertThat(storage.exists(addressOf(statement))).as("the statement's copy, reclaimed by the existing path").isFalse();
        for (ImportJob job : List.of(completed, failed)) {
            assertThat(rowsNaming(job.getObjectKey())).as("rows naming job %s's object", job.getId()).isZero();
            assertThat(storage.exists(addressOf(job)))
                    .as("job %s's object, with no row left anywhere naming it", job.getId())
                    .isFalse();
        }
    }

    /**
     * BH-039 for the purge path: the purged user's own jobs stop counting, nobody else's do. The
     * other user's FAILED job is given the purged user's key directly -- see the shared-key test
     * above for why that has to be arranged.
     */
    @Test
    void accountPurge_keepsAnObjectAnotherUsersJobStillHolds() throws Exception {
        Owner leaving = owner();
        Owner staying = owner();
        ImportJob leavingJob = accept(leaving, csv());
        ImportJob stayingJob = accept(staying, csv());
        jdbcTemplate.update("UPDATE import_jobs SET status = 'FAILED', finished_at = now(), object_key = ? WHERE id = ?",
                leavingJob.getObjectKey(), stayingJob.getId());

        accountPurgeSweepService.adminPurge(leaving.userId(), UUID.randomUUID());

        assertThat(importJobRepository.findById(leavingJob.getId())).isEmpty();
        assertThat(storage.exists(addressOf(leavingJob)))
                .as("still the other user's failed import, retryable without re-upload")
                .isTrue();
    }
}
