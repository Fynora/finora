package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.entity.Account;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HeldStatementService#recordFindings} and {@link HeldStatementService#rerunParser} --
 * Plan 3 of the Held Statement Review System. Uses real, storage-backed bytes (BH-045:
 * {@code ImportJob.getFileContent()} always returns null) and real CSV parsing throughout, same
 * discipline as {@code AdminHeldStatementDownloadIT}.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-statement-rerun-it",
        "app.statement-passwords.save.enabled=true"
})
class HeldStatementServiceRerunIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementService heldStatementService;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementEventRepository eventRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementStorage storage;
    @Autowired private com.finora.imports.ImportSessionService importSessionService;
    @Autowired private com.finora.repository.ImportSessionRepository importSessionRepository;
    @Autowired private com.finora.repository.StatementImportRepository statementImportRepository;
    @Autowired private com.finora.repository.AccountRepository accountRepository;
    @Autowired private com.finora.notification.repository.NotificationRepository notificationRepository;
    @Autowired private com.finora.imports.passwords.StatementPasswordService statementPasswordService;

    private static final byte[] CLEAN_CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);

    /** Statement period 2030 is safely in the future relative to any real "today" this suite will
     *  ever run on -- a robust, non-flaky trigger for TrustPredicate's periodIntegrity check. */
    private static final byte[] FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    /** Period end (20/01/2026) is AFTER the hold's own createdAt (10/01/2026, backdated in the
     *  test) but well BEFORE the real date this suite runs on -- the exact shape that only a
     *  today-anchored evaluation flags correctly. */
    private static final byte[] NEAR_FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,10/01/2026 to 20/01/2026\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    private User user() {
        User user = new User();
        user.setEmail("rerun-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Rerun IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** {@code held_statement_events.actor_id} has a real FK to {@code users(id)} (V144) -- a
     *  random UUID is refused, not merely unrealistic. Every call into the service under test
     *  needs a real, persisted admin id. */
    private UUID admin() {
        return user().getId();
    }

    private HeldStatement seedHold(byte[] bytes) {
        return seedHold(bytes, "statement.csv", "CSV");
    }

    private HeldStatement seedHold(byte[] bytes, String fileName, String sourceFormat) {
        User owner = user();
        ContentAddress address = storage.store(bytes);
        ImportJob job = new ImportJob(owner.getId(), fileName, address.hash(), address.key(), sourceFormat);
        job.markClaimed("worker", Instant.now());
        UUID sessionId = stagedSession(owner.getId(), bytes);
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        // One save, matching HeldStatementService.openHold's own sequence exactly (construct,
        // recordSnapshot, recordBank, THEN save) -- two separate saves here previously left the
        // in-memory entity's @Version out of step with what seedHoldWithCreatedAt's later save
        // expected, and Hibernate correctly rejected the stale write.
        HeldStatement held = new HeldStatement(
                "HLD-2026-9" + System.nanoTime() % 100000, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)");
        held.recordSnapshot("old-build", null, null, null, List.of("COUNT_MISMATCH"));
        held = heldStatementRepository.save(held);
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    private HeldStatement seedHoldWithCreatedAt(byte[] bytes, Instant createdAt) {
        HeldStatement held = seedHold(bytes);
        ReflectionTestUtils.setField(held, "createdAt", createdAt);
        return heldStatementRepository.save(held);
    }

    // --- recordFindings ---------------------------------------------------------------------------

    @Test
    void recordFindingsSavesBothFieldsAndWritesAnEvent() {
        HeldStatement held = seedHold(CLEAN_CSV);

        var result = heldStatementService.recordFindings(admin(), held.getHeldId(),
                "Two-line HSBC header confused the column locator", "PR #950");

        assertThat(result.rootCause()).isEqualTo("Two-line HSBC header confused the column locator");
        assertThat(result.fixReference()).isEqualTo("PR #950");
        List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        assertThat(events).extracting(HeldStatementEvent::getEventType).contains("FINDINGS_UPDATED");
    }

    // --- rerunParser: clearing --------------------------------------------------------------------

    @Test
    void rerunParserMarksReadyForImportWhenTheCurrentBuildNoLongerTriggersTheHold() {
        HeldStatement held = seedHold(CLEAN_CSV);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isFalse();
        assertThat(result.reasons()).isEmpty();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
    }

    @Test
    void rerunParserWritesNoNewVerificationFindingRows() {
        HeldStatement held = seedHold(CLEAN_CSV);
        long findingsBefore = findingRepository.count();

        heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(findingRepository.count()).isEqualTo(findingsBefore);
    }

    @Test
    void rerunParserIsIdempotentOnAnAlreadyClearedHold() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.rerunParser(admin(), held.getHeldId());

        HeldStatementRerunResultDto second = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(second.stillHeld()).isFalse();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
    }

    // --- rerunParser: still held -------------------------------------------------------------------

    @Test
    void rerunParserLeavesTheHoldAloneWhenTheProblemStillReproduces() {
        HeldStatement held = seedHold(FUTURE_PERIOD_CSV);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isTrue();
        assertThat(result.reasons()).isNotEmpty();
        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.HELD);
    }

    // --- rerunParser: anchored today ---------------------------------------------------------------

    @Test
    void rerunParserAnchorsPeriodIntegrityToTheOriginalHoldDateNotToday() {
        Instant heldAt = Instant.parse("2026-01-10T00:00:00Z");
        HeldStatement held = seedHoldWithCreatedAt(NEAR_FUTURE_PERIOD_CSV, heldAt);

        HeldStatementRerunResultDto result = heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(result.stillHeld()).isTrue();
        assertThat(result.reasons()).anyMatch(r -> r.contains("future"));
    }

    // --- rerunParser: event richness ---------------------------------------------------------------

    @Test
    void rerunParserEventRecordsBothParserVersionsAndWhetherTheyChanged() {
        HeldStatement held = seedHold(CLEAN_CSV);

        heldStatementService.rerunParser(admin(), held.getHeldId());

        List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        HeldStatementEvent rerun = events.stream()
                .filter(e -> "PARSER_RERUN".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(rerun.getNotes()).contains("old-build");
        assertThat(rerun.getNotes()).containsIgnoringCase("parser version");
    }

    // --- rerunParser: refuses an already-resolved hold --------------------------------------------

    @Test
    void rerunParserRefusesAnAlreadyResolvedHoldWithAConflictRatherThanCorruptingState() {
        // A rerun attempted after another actor already resolved the same hold (e.g. a concurrent
        // approve that landed first) must not silently proceed -- refuseIfResolved's own 409 is
        // what stops it, exactly the same guard approve/reject already rely on.
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.approve(admin(), held.getHeldId(), null, null);

        assertThatThrownBy(() -> heldStatementService.rerunParser(admin(), held.getHeldId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot be");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
    }

    // --- approve: explicit false-positive marking -------------------------------------------------

    @Test
    void approveRecordsFalsePositiveOnTheHoldAndInTheEvent() {
        HeldStatement held = seedHold(CLEAN_CSV);

        heldStatementService.approve(admin(), held.getHeldId(), "looked fine after all", true);

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getFalsePositive()).isTrue();
        List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        HeldStatementEvent approved = events.stream()
                .filter(e -> "APPROVED".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(approved.getNotes()).containsIgnoringCase("false positive");
    }

    @Test
    void approveWithNoFalsePositiveArgumentLeavesItUnmarked() {
        // The existing (pre-Plan-4) approve call sites all pass null here -- confirms the widened
        // signature does not silently start marking every approval one way or another.
        HeldStatement held = seedHold(CLEAN_CSV);

        heldStatementService.approve(admin(), held.getHeldId(), "note only", null);

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getFalsePositive()).isNull();
    }

    /**
     * Documents the Decisions-table call: reject's signature is unchanged by this plan. This is a
     * design-intent test, not a strong runtime guard -- mutation-tested directly (Task 2 Step 9):
     * a reject(..., Boolean falsePositive) overload that silently ignores the new parameter still
     * makes every assertion here pass, because this test never calls it. Catching a future edit
     * that widens reject's signature and wires it up wrongly is better done by code review than by
     * a runtime assertion; this test is the design-intent marker for that review, not a substitute.
     */
    @Test
    void rejectHasNoFalsePositiveParameter() {
        HeldStatement held = seedHold(CLEAN_CSV);

        heldStatementService.reject(admin(), held.getHeldId(), "genuinely bad extraction");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getFalsePositive()).isNull();
    }

    // --- HeldStatement.version: genuine optimistic-locking coverage --------------------------------

    @Test
    void concurrentWritesToTheSameHeldStatementAreCaughtByOptimisticLocking() {
        // Two separately-loaded copies of the same row, simulating two admins who both opened the
        // hold before either saved -- exactly the scenario @Version exists for. The first save
        // advances the row's version; the second, now-stale, save must be rejected rather than
        // silently overwriting the first admin's change.
        HeldStatement held = seedHold(CLEAN_CSV);
        UUID id = held.getId();

        HeldStatement copyA = heldStatementRepository.findById(id).orElseThrow();
        HeldStatement copyB = heldStatementRepository.findById(id).orElseThrow();
        assertThat(copyA.getVersion()).isEqualTo(copyB.getVersion());

        copyA.addNotes("first admin's note");
        heldStatementRepository.saveAndFlush(copyA);

        copyB.addNotes("second admin's stale note");
        assertThatThrownBy(() -> heldStatementRepository.saveAndFlush(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // --- rerunParser: the rows approving releases --------------------------------------------------

    private ImportJob jobOf(HeldStatement held) {
        return importJobRepository.findById(held.getImportJobId()).orElseThrow();
    }

    @Test
    void aRerunThatClearsReplacesTheRowsStagedWhenHeldWithThisBuildsReading() {
        HeldStatement held = seedHold(CLEAN_CSV);
        UUID heldSession = jobOf(held).getImportSessionId();
        assertThat(importSessionService.readStagedRows(importSessionRepository.findById(heldSession).orElseThrow()))
                .isEmpty();

        heldStatementService.rerunParser(admin(), held.getHeldId());

        ImportJob job = jobOf(held);
        assertThat(job.getImportSessionId()).isNotEqualTo(heldSession);
        assertThat(importSessionRepository.existsById(heldSession)).isFalse();
        var rows = importSessionService.readStagedRows(importSessionRepository.findById(job.getImportSessionId()).orElseThrow());
        assertThat(rows).extracting(row -> row.description()).anyMatch(d -> d.contains("Coffee shop"));
        assertThat(job.getRowsProcessed()).isEqualTo(rows.size());
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    }

    @Test
    void approvingAfterAClearingRerunReleasesTheReReadRows() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.rerunParser(admin(), held.getHeldId());
        UUID reRead = jobOf(held).getImportSessionId();

        heldStatementService.approve(admin(), held.getHeldId(), null, null);

        ImportJob job = jobOf(held);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job.getImportSessionId()).isEqualTo(reRead);
    }

    @Test
    void aRerunThatStillHoldsLeavesTheStagedRowsAlone() {
        HeldStatement held = seedHold(FUTURE_PERIOD_CSV);
        UUID heldSession = jobOf(held).getImportSessionId();

        heldStatementService.rerunParser(admin(), held.getHeldId());

        assertThat(jobOf(held).getImportSessionId()).isEqualTo(heldSession);
        assertThat(importSessionRepository.existsById(heldSession)).isTrue();
    }

    @Test
    void approveRefusesWhenTheStagedRowsAreGone() {
        HeldStatement held = seedHold(CLEAN_CSV);
        importSessionRepository.deleteById(jobOf(held).getImportSessionId());

        assertThatThrownBy(() -> heldStatementService.approve(admin(), held.getHeldId(), null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Re-run the parser");
        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    }

    // --- reopen -------------------------------------------------------------------------------------

    @Test
    void reopenReturnsARejectedHoldToTheQueueAndItsImportToHeld() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.reject(admin(), held.getHeldId(), "misread");
        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.FAILED);

        heldStatementService.reopen(admin(), held.getHeldId(), "parser fixed");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.INVESTIGATING);
        assertThat(reloaded.getResolvedAt()).isNull();
        ImportJob job = jobOf(held);
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(job.getFailureCode()).isNull();
        assertThat(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId()))
                .extracting(HeldStatementEvent::getEventType).containsSubsequence("REJECTED", "REOPENED");
    }

    /**
     * Gate 1 spec §4: the user waits again from the reopening, so the 48-hour clock restarts there
     * and the earlier hold's escalation marker no longer counts. Left at the rejection time, a
     * review reopened days later would read as overdue at once with its escalation suppressed.
     */
    @Test
    void reopeningRestartsTheHoldClockAndClearsTheEscalationMarker() {
        HeldStatement held = seedHold(CLEAN_CSV);
        ImportJob before = jobOf(held);
        before.markOverdueAlerted(java.time.Instant.now().minus(java.time.Duration.ofDays(6)));
        importJobRepository.save(before);
        heldStatementService.reject(admin(), held.getHeldId(), "misread");
        // Rejected five days ago: reopening must not inherit that as the start of the new hold.
        java.time.Instant rejectedAt = java.time.Instant.now().minus(java.time.Duration.ofDays(5));
        jdbcTemplate.update("UPDATE import_jobs SET finished_at = ? WHERE id = ?",
                java.sql.Timestamp.from(rejectedAt), before.getId());

        heldStatementService.reopen(admin(), held.getHeldId(), "parser fixed");

        ImportJob job = jobOf(held);
        assertThat(job.getFinishedAt()).isAfter(rejectedAt.plus(java.time.Duration.ofDays(4)));
        assertThat(job.getOverdueAlertedAt()).isNull();
        assertThat(job.isHoldOverdue(java.time.Instant.now())).isFalse();
    }

    @Test
    void aReopenedHoldWhoseRowsWereSweptIsReReadAndApproved() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.reject(admin(), held.getHeldId(), null);
        importSessionRepository.deleteById(jobOf(held).getImportSessionId());
        heldStatementService.reopen(admin(), held.getHeldId(), null);

        assertThatThrownBy(() -> heldStatementService.approve(admin(), held.getHeldId(), null, null))
                .isInstanceOf(ApiException.class);
        heldStatementService.rerunParser(admin(), held.getHeldId());
        heldStatementService.approve(admin(), held.getHeldId(), null, null);

        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(importSessionRepository.existsById(jobOf(held).getImportSessionId())).isTrue();
    }

    @Test
    void reopenRefusesAHoldThatWasNotRejected() {
        HeldStatement open = seedHold(CLEAN_CSV);
        assertThatThrownBy(() -> heldStatementService.reopen(admin(), open.getHeldId(), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("only a rejected hold");

        HeldStatement imported = seedHold(CLEAN_CSV);
        heldStatementService.approve(admin(), imported.getHeldId(), null, null);
        assertThatThrownBy(() -> heldStatementService.reopen(admin(), imported.getHeldId(), null))
                .isInstanceOf(ApiException.class);
        assertThat(jobOf(imported).getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
    }

    // --- the user uploaded the statement again -----------------------------------------------------

    @Test
    void reopenRefusesWhenTheUserImportedTheStatementAgainSinceAndAnEarlierImportDoesNot() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.reject(admin(), held.getHeldId(), null);
        ImportJob job = jobOf(held);
        confirmedImportOf(job, job.getCreatedAt().minusSeconds(60));

        heldStatementService.reopen(admin(), held.getHeldId(), null);
        heldStatementService.reject(admin(), held.getHeldId(), null);
        confirmedImportOf(job, job.getCreatedAt().plusSeconds(60));

        assertThatThrownBy(() -> heldStatementService.reopen(admin(), held.getHeldId(), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("imported this statement again");
        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void approveRefusesWhenTheUserImportedTheStatementAgainSince() {
        HeldStatement held = seedHold(CLEAN_CSV);
        confirmedImportOf(jobOf(held), Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> heldStatementService.approve(admin(), held.getHeldId(), null, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("imported this statement again");
        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    }

    /** The re-upload guards tell the operator to reject a superseded hold; that rejection must not
     *  tell the user "your statement wasn't imported" about a statement they have imported. */
    @Test
    void rejectingAHoldTheUserImportedAgainSendsNoNoticeAndAnOrdinaryRejectionDoes() {
        HeldStatement superseded = seedHold(CLEAN_CSV);
        confirmedImportOf(jobOf(superseded), Instant.now().plusSeconds(60));
        HeldStatement ordinary = seedHold(CLEAN_CSV);

        heldStatementService.reject(admin(), superseded.getHeldId(), null);
        heldStatementService.reject(admin(), ordinary.getHeldId(), null);

        assertThat(jobOf(superseded).getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_REJECTED_" + superseded.getImportJobId() + ":EMAIL")).isEmpty();
        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_REJECTED_" + superseded.getImportJobId() + ":PUSH")).isEmpty();
        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_REJECTED_" + ordinary.getImportJobId() + ":EMAIL")).isPresent();
    }

    /** A re-upload under a newer build deletes the held session as stale and stages its own, which
     *  then holds the one V79 slot: without the check the re-read's insert hits the constraint. */
    @Test
    void aRerunRefusesWhenAnotherUploadOfTheStatementIsStaged() {
        HeldStatement held = seedHold(CLEAN_CSV);
        ImportJob job = jobOf(held);
        importSessionRepository.deleteById(job.getImportSessionId());
        UUID usersOwn = stagedSession(job.getUserId(), CLEAN_CSV);

        assertThatThrownBy(() -> heldStatementService.rerunParser(admin(), held.getHeldId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("rows are still staged");
        assertThat(importSessionRepository.existsById(usersOwn)).isTrue();
        assertThat(jobOf(held).getImportSessionId()).isEqualTo(job.getImportSessionId());
        assertThat(heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow().getStatus())
                .isEqualTo(HeldStatement.Status.HELD);
    }

    @Test
    void reopenRefusesWhileAnotherUploadOfTheStatementIsStillRunning() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.reject(admin(), held.getHeldId(), null);
        ImportJob job = jobOf(held);
        importJobRepository.save(new ImportJob(job.getUserId(), "again.csv", job.getContentHash(),
                job.getObjectKey(), "CSV"));

        assertThatThrownBy(() -> heldStatementService.reopen(admin(), held.getHeldId(), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("still running");
    }

    // --- a locked statement whose saved password is gone ---------------------------------------------

    @Test
    void reopenRefusesALockedStatementWhoseSavedPasswordWasDeleted() throws Exception {
        HeldStatement held = seedHold(lockedPdf("open-sesame"), "statement.pdf", "PDF");
        heldStatementService.reject(admin(), held.getHeldId(), null);

        assertThatThrownBy(() -> heldStatementService.reopen(admin(), held.getHeldId(), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("password-protected");
        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void reopenKeepsALockedStatementWhosePasswordIsStillSaved() throws Exception {
        HeldStatement held = seedHold(lockedPdf("open-sesame"), "statement.pdf", "PDF");
        statementPasswordService.saveForJob(held.getUserId(), held.getImportJobId(), "open-sesame");
        heldStatementService.reject(admin(), held.getHeldId(), null);

        heldStatementService.reopen(admin(), held.getHeldId(), null);

        assertThat(jobOf(held).getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    }

    private StatementImport confirmedImportOf(ImportJob job, Instant importedAt) {
        Account account = new Account();
        account.setUserId(job.getUserId());
        account.setName("Rerun IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(java.math.BigDecimal.ZERO);
        StatementImport statement = new StatementImport();
        statement.setUserId(job.getUserId());
        statement.setAccountId(accountRepository.save(account).getId());
        statement.setFileName(job.getFileName());
        statement.setSourceFormat(job.getSourceFormat());
        statement.setFileContent(new byte[]{1});
        statement.setContentHash(job.getContentHash());
        statement.setImportedAt(importedAt);
        return statementImportRepository.save(statement);
    }

    private static byte[] lockedPdf(String password) throws java.io.IOException {
        try (PDDocument document = new PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-" + password, password, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            return out.toByteArray();
        }
    }

    // --- the resolved list --------------------------------------------------------------------------

    @Test
    void aRejectedHoldLeavesTheOpenListAndAppearsInTheResolvedOne() {
        HeldStatement held = seedHold(CLEAN_CSV);
        heldStatementService.reject(admin(), held.getHeldId(), null);
        var filter = new HeldStatementFilter(null, null, null, null);

        assertThat(heldStatementService.list(0, 200, filter).content())
                .extracting(dto -> dto.heldId()).doesNotContain(held.getHeldId());
        assertThat(heldStatementService.listResolved(0, 200, filter).content())
                .extracting(dto -> dto.heldId()).contains(held.getHeldId());
        assertThat(heldStatementService.listResolved(0, 200,
                new HeldStatementFilter(HeldStatement.Status.HELD, null, null, null)).content())
                .extracting(dto -> dto.heldId()).doesNotContain(held.getHeldId());
    }

    /** A real staged session, as the worker leaves behind a held import: approving a hold whose
     *  session does not exist is refused, so a made-up id would make every approval a 409. */
    private UUID stagedSession(UUID ownerId, byte[] content) {
        return importSessionService.createSession(ownerId, "statement.csv", content,
                java.util.List.of(), null).getId();
    }
}
