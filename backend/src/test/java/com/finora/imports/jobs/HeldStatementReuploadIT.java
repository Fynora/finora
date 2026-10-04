package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same statement arriving again while, or after, a trust review is held on it.
 *
 * <p>Staging replays a live session for the same bytes under the same build, so a second job used
 * to carry the held job's own session into the trust predicate and open a second review of it.
 * {@code ImportSessionService.sessionsBlockedByTrustReview} blocks a session while any hold on it
 * is not IMPORTED, so approving the first review left the user unable to confirm. Under a newer
 * build, staging deleted the held session as stale instead, and approving completed the held job
 * against rows that no longer existed.
 *
 * <p>Driven end to end: real uploads through {@code POST /api/v1/import/jobs}, the real worker,
 * and the real confirm gate. The statement period in 2030 is what makes the trust predicate hold
 * -- the same trigger {@code HeldStatementServiceRerunIT} uses.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-reupload-it",
        "app.import.queue.enabled=false"
})
class HeldStatementReuploadIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementService heldStatementService;
    @Autowired private AdminHeldImportService adminHeldImportService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportService importService;
    @Autowired private ImportJobWorker worker;
    @Autowired private JwtService jwtService;
    @Autowired private com.finora.repository.RefreshTokenRepository refreshTokens;
    @Autowired private com.finora.repository.HeldStatementEventRepository heldStatementEventRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** Nothing in it trips the trust predicate -- the same document HeldStatementServiceRerunIT
     *  uses for a statement that clears. */
    private static final byte[] CLEAN_CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);

    private User user() {
        User user = new User();
        user.setEmail("held-reupload-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Reupload IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private UUID upload(User user) throws Exception {
        return upload(user, FUTURE_PERIOD_CSV);
    }

    private UUID upload(User user, byte[] content) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(
                com.finora.testsupport.TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }

    /** Uploads and runs the worker, asserting the statement was held -- the starting state of
     *  every case here. */
    private ImportJob heldUpload(User owner) throws Exception {
        UUID jobId = upload(owner);
        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(job.getImportSessionId()).isNotNull();
        return job;
    }

    private List<HeldStatement> holdsOf(User owner) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(owner.getId(), PageRequest.of(0, 20)).stream()
                .flatMap(job -> heldStatementRepository.findByImportJobId(job.getId()).stream())
                .toList();
    }

    private String heldIdOf(ImportJob job) {
        return heldStatementRepository.findByImportJobId(job.getId()).orElseThrow().getHeldId();
    }

    /** A different build staged this user's sessions -- what a deploy between the two uploads
     *  looks like to findLiveSessionByContentHash. */
    private void markSessionsStagedByAnOlderBuild(User owner) {
        for (ImportSession session : sessionRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())) {
            ReflectionTestUtils.setField(session, "parserVersion", "0ldbld0");
            sessionRepository.save(session);
        }
    }

    /** The user can reach confirm for this session: it is offered for resume and the claim the
     *  confirm endpoint makes first succeeds. */
    private void assertConfirmable(User owner, UUID sessionId) {
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).contains(sessionId);
        assertThat(importSessionService.claimForConfirmation(owner.getId(), sessionId).getStatus())
                .isEqualTo(ImportSession.STATUS_CONFIRMED);
    }

    @Test
    void reuploadWhileHeldReturnsTheHeldJobAndApprovingItLetsTheUserConfirm() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);

        UUID again = upload(owner);
        worker.drainOnce();

        assertThat(again).as("the re-upload follows the job already under review").isEqualTo(held.getId());
        assertThat(jobRepository.findByUserIdOrderByCreatedAtDesc(owner.getId(), PageRequest.of(0, 20)))
                .hasSize(1);
        assertThat(holdsOf(owner)).hasSize(1);

        heldStatementService.approve(user().getId(), heldIdOf(held), null, null);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void reuploadUnderANewerBuildWhileHeldKeepsTheSessionTheReviewerIsJudging() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        markSessionsStagedByAnOlderBuild(owner);

        UUID again = upload(owner);
        worker.drainOnce();
        // The synchronous stage endpoint's path reaches the same lookup with no job in front of it.
        UUID syncSession = importService
                .parseAndStageWithSession(owner.getId(), "statement.csv", FUTURE_PERIOD_CSV).sessionId();

        assertThat(again).isEqualTo(held.getId());
        assertThat(syncSession).as("replayed, not deleted and re-staged").isEqualTo(held.getImportSessionId());
        assertThat(sessionRepository.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                .extracting(ImportSession::getId).containsExactly(held.getImportSessionId());
        assertThat(holdsOf(owner)).hasSize(1);

        heldStatementService.approve(user().getId(), heldIdOf(held), null, null);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void reprocessingAnOlderJobOnTheSameBytesIsRefusedWhileTheTrustReviewIsOpen() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        ImportJob older = new ImportJob(owner.getId(), "statement.csv", held.getContentHash(),
                held.getObjectKey(), "CSV", held.getEncryptionKeyId());
        older.markClaimed("test", Instant.now());
        older.holdForReview("IllegalStateException", Instant.now());
        UUID olderId = jobRepository.save(older).getId();

        assertThatThrownBy(() -> adminHeldImportService.reprocess(user().getId(), olderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("held for trust review");
        assertThat(jobRepository.findById(olderId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(holdsOf(owner)).hasSize(1);
    }

    @Test
    void reuploadAfterApprovalCompletesOnTheApprovedSessionWithoutASecondReview() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        heldStatementService.approve(user().getId(), heldIdOf(held), null, null);

        UUID againId = upload(owner);
        worker.drainOnce();

        ImportJob again = jobRepository.findById(againId).orElseThrow();
        assertThat(againId).isNotEqualTo(held.getId());
        assertThat(again.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(again.getImportSessionId()).isEqualTo(held.getImportSessionId());
        assertThat(again.getHeldStatementId()).isNull();
        assertThat(holdsOf(owner)).hasSize(1);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void reuploadAfterRejectionFailsTheWayTheRejectedImportDid() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        heldStatementService.reject(user().getId(), heldIdOf(held), "rows do not match the document");

        UUID againId = upload(owner);
        worker.drainOnce();

        ImportJob again = jobRepository.findById(againId).orElseThrow();
        assertThat(againId).isNotEqualTo(held.getId());
        assertThat(again.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(again.getFailureCode()).isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());
        assertThat(again.getImportSessionId())
                .as("rides the rejected review, as reject() leaves a job it decides")
                .isEqualTo(held.getImportSessionId());
        assertThat(again.getHeldStatementId()).isNull();
        assertThat(holdsOf(owner)).extracting(HeldStatement::getStatus)
                .containsExactly(HeldStatement.Status.REJECTED);
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).doesNotContain(held.getImportSessionId());
    }

    /** Reopening the rejected review (#1997) holds the later upload again with it, and approving
     *  then releases both -- not one completed and one left failed for rows that were imported. */
    @Test
    void reopeningARejectedReviewCarriesTheLaterUploadOfTheSameRows() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        String heldId = heldIdOf(held);
        heldStatementService.reject(user().getId(), heldId, "rows do not match the document");
        UUID againId = upload(owner);
        worker.drainOnce();

        heldStatementService.reopen(user().getId(), heldId, "parser fix shipped");
        assertThat(jobRepository.findById(againId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);

        heldStatementService.approve(user().getId(), heldId, null, null);
        assertThat(jobRepository.findById(againId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void aHeldSessionPastItsExpiryIsReplayedRatherThanDeleted() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        ImportSession session = sessionRepository.findById(held.getImportSessionId()).orElseThrow();
        ReflectionTestUtils.setField(session, "expiresAt", Instant.now().minusSeconds(60));
        sessionRepository.save(session);

        UUID syncSession = importService
                .parseAndStageWithSession(owner.getId(), "statement.csv", FUTURE_PERIOD_CSV).sessionId();

        assertThat(syncSession).isEqualTo(held.getImportSessionId());
        assertThat(sessionRepository.findById(held.getImportSessionId())).isPresent();
    }

    @Test
    void priorReviewOfIgnoresTheCallersOwnHold() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);

        assertThat(heldStatementService.priorReviewOf(held.getImportSessionId(), held.getId()))
                .as("a retried pass of the held job itself").isEmpty();
        assertThat(heldStatementService.priorReviewOf(held.getImportSessionId(), UUID.randomUUID()))
                .contains(HeldStatement.Status.HELD);
    }

    /**
     * The worker fails closed when it cannot write the hold record: the job is held anyway, with
     * {@code heldStatementId} null and nothing in the operator queue. A re-upload is that user's
     * only way to a review an operator can act on, so it must still start one rather than follow
     * the record-less job forever.
     */
    /** The state ImportJobWorker leaves when createHold throws: its REQUIRES_NEW transaction rolls
     *  back, so no held_statements row exists, and the job is held with heldStatementId null. */
    private ImportJob heldWithNoReviewRecord(User owner) throws Exception {
        ImportJob held = heldUpload(owner);
        HeldStatement record = heldStatementRepository.findByImportJobId(held.getId()).orElseThrow();
        heldStatementEventRepository.deleteAll(
                heldStatementEventRepository.findByHeldStatementIdOrderByCreatedAtAsc(record.getId()));
        heldStatementRepository.delete(record);
        ImportJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
        ReflectionTestUtils.setField(reloaded, "heldStatementId", null);
        return jobRepository.save(reloaded);
    }

    @Test
    void aJobHeldWithNoReviewRecordStillKeepsItsRowsFromTheLedger() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);

        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).doesNotContain(held.getImportSessionId());
        assertThatThrownBy(() -> importSessionService.claimForConfirmation(owner.getId(), held.getImportSessionId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.IMPORT_SESSION_HELD_FOR_REVIEW);
        // The atomic claim is the enforcement, not the read above it -- asked directly, so the
        // service's fast-path check cannot answer for it.
        Integer claimed = transactionTemplate.execute(
                status -> sessionRepository.claimForConfirmation(held.getImportSessionId()));
        assertThat(claimed).isZero();
        assertThat(sessionRepository.findById(held.getImportSessionId()).orElseThrow().getStatus())
                .isEqualTo(ImportSession.STATUS_STAGED);
    }

    @Test
    void reuploadOfAJobHeldWithNoReviewRecordStillReachesAReview() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);

        UUID againId = upload(owner);
        worker.drainOnce();

        ImportJob again = jobRepository.findById(againId).orElseThrow();
        assertThat(againId).isNotEqualTo(held.getId());
        assertThat(again.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(again.getImportSessionId()).isEqualTo(held.getImportSessionId());
        heldStatementService.approve(user().getId(), heldIdOf(again), null, null);
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .as("the record-less hold is decided by the review that covers its rows")
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, held.getImportSessionId());
    }

    /** A clean statement whose job was nevertheless held with no record -- so the replay's own
     *  predicate would release it, which is exactly when the review must be forced. */
    private ImportJob cleanJobHeldWithNoReviewRecord(User owner) throws Exception {
        UUID jobId = upload(owner, CLEAN_CSV);
        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        job.holdForTrustReview(job.getImportSessionId(), null, Instant.now());
        return jobRepository.save(job);
    }

    @Test
    void aReplayOverARecordLessHoldIsHeldEvenWhenItsOwnChecksPass() throws Exception {
        User owner = user();
        ImportJob held = cleanJobHeldWithNoReviewRecord(owner);

        UUID againId = upload(owner, CLEAN_CSV);
        worker.drainOnce();

        ImportJob again = jobRepository.findById(againId).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(again.getImportSessionId()).isEqualTo(held.getImportSessionId());
        assertThat(heldStatementRepository.findByImportJobId(againId).orElseThrow().getTriggerSummary())
                .contains("no review record");

        heldStatementService.approve(user().getId(), heldIdOf(again), null, null);
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void rejectingTheCoveringReviewRejectsTheRecordLessHoldToo() throws Exception {
        User owner = user();
        ImportJob held = cleanJobHeldWithNoReviewRecord(owner);
        UUID againId = upload(owner, CLEAN_CSV);
        worker.drainOnce();

        heldStatementService.reject(user().getId(), heldIdOf(jobRepository.findById(againId).orElseThrow()),
                "rows do not match the document");

        ImportJob first = jobRepository.findById(held.getId()).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(first.getFailureCode()).isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).doesNotContain(held.getImportSessionId());
    }

    private ResponseEntity<String> discard(User owner, UUID sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(
                com.finora.testsupport.TestSessions.accessTokenFor(jwtService, refreshTokens, owner));
        return restTemplate.exchange("/api/v1/import/sessions/" + sessionId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void theUserCannotDiscardASessionWhileItsTrustReviewIsOpen() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);

        ResponseEntity<String> refused = discard(owner, held.getImportSessionId());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sessionRepository.findById(held.getImportSessionId())).isPresent();
        heldStatementService.approve(user().getId(), heldIdOf(held), null, null);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void theUserCanDiscardTheSessionOnceTheReviewIsDecided() throws Exception {
        User owner = user();
        ImportJob held = heldUpload(owner);
        heldStatementService.approve(user().getId(), heldIdOf(held), null, null);

        ResponseEntity<String> discarded = discard(owner, held.getImportSessionId());

        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionRepository.findById(held.getImportSessionId())).isEmpty();
    }

    @Test
    void aRerunThatReplacesTheSessionCarriesTheRecordLessHoldWithIt() throws Exception {
        User owner = user();
        ImportJob held = cleanJobHeldWithNoReviewRecord(owner);
        UUID againId = upload(owner, CLEAN_CSV);
        worker.drainOnce();
        String heldId = heldIdOf(jobRepository.findById(againId).orElseThrow());

        // CLEAN_CSV passes the predicate, so the re-run clears and stages a new session.
        assertThat(heldStatementService.rerunParser(user().getId(), heldId).stillHeld()).isFalse();

        UUID restaged = jobRepository.findById(againId).orElseThrow().getImportSessionId();
        assertThat(restaged).isNotEqualTo(held.getImportSessionId());
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getImportSessionId())
                .as("the record-less hold follows its rows to the new session")
                .isEqualTo(restaged);
        heldStatementService.approve(user().getId(), heldId, null, null);
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, restaged);
    }

    @Test
    void reopeningTheRejectedReviewHoldsTheRecordLessJobAgain() throws Exception {
        User owner = user();
        ImportJob held = cleanJobHeldWithNoReviewRecord(owner);
        UUID againId = upload(owner, CLEAN_CSV);
        worker.drainOnce();
        String heldId = heldIdOf(jobRepository.findById(againId).orElseThrow());
        heldStatementService.reject(user().getId(), heldId, "rows do not match the document");

        heldStatementService.reopen(user().getId(), heldId, "parser fix shipped");

        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        heldStatementService.approve(user().getId(), heldId, null, null);
        assertThat(jobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void anOperatorOpensTheMissingReviewAndApprovingItLetsTheUserConfirm() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);

        String heldId = heldStatementService.openReviewForHoldWithoutRecord(user().getId(), held.getId()).heldId();

        // The review the worker failed to write, reading the trigger back from the held rows.
        assertThat(heldStatementRepository.findByHeldId(heldId).orElseThrow().getTriggerSummary())
                .contains("Held without a review record").contains("future");
        assertThat(heldStatementService.listHoldsWithoutReviewRecord(0, 200).content())
                .noneMatch(row -> row.jobId().equals(held.getId()));
        heldStatementService.approve(user().getId(), heldId, null, null);
        assertConfirmable(owner, held.getImportSessionId());
    }

    @Test
    void anOperatorOpensTheMissingReviewAndRejectingItFailsTheImport() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);
        String heldId = heldStatementService.openReviewForHoldWithoutRecord(user().getId(), held.getId()).heldId();

        heldStatementService.reject(user().getId(), heldId, "rows do not match the document");

        ImportJob reloaded = jobRepository.findById(held.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(reloaded.getFailureCode()).isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());
        assertThat(importSessionService.listResumableSessions(owner.getId()))
                .extracting(ImportSession::getId).doesNotContain(held.getImportSessionId());
    }

    /** A record-less hold whose rows a re-upload's review already covers: listed as covered, and
     *  opening a second review on the same session is refused. */
    @Test
    void aRecordLessHoldAlreadyCoveredByAReviewIsNotGivenASecondOne() throws Exception {
        User owner = user();
        ImportJob held = cleanJobHeldWithNoReviewRecord(owner);
        UUID againId = upload(owner, CLEAN_CSV);
        worker.drainOnce();
        String coveringHeldId = heldIdOf(jobRepository.findById(againId).orElseThrow());

        assertThat(heldStatementService.listHoldsWithoutReviewRecord(0, 200).content())
                .filteredOn(row -> row.jobId().equals(held.getId()))
                .singleElement()
                .extracting(com.finora.dto.HoldWithoutReviewRecordDto::coveredByHeldId)
                .isEqualTo(coveringHeldId);
        assertThatThrownBy(() -> heldStatementService.openReviewForHoldWithoutRecord(user().getId(), held.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(coveringHeldId);
        assertThat(holdsOf(owner)).hasSize(1);
    }

    /**
     * The interleaving an admin opening a review can race: a re-upload's worker pass has already
     * read "held with no record" and decided to hold, and the admin's review commits before that
     * pass calls createHold. The pass must not write a second review on the same rows.
     */
    @Test
    void aWorkerHoldRacingAnAdminOpenedReviewDoesNotWriteASecondOne() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);
        ImportJob reupload = new ImportJob(owner.getId(), "statement.csv", held.getContentHash(),
                held.getObjectKey(), "CSV", held.getEncryptionKeyId());
        reupload.markClaimed("worker", Instant.now());
        reupload = jobRepository.save(reupload);
        StagedForJob replayed = new StagedForJob(held.getImportSessionId(), 2, 2, null, List.of(), List.of());

        String adminHeldId = heldStatementService.openReviewForHoldWithoutRecord(user().getId(), held.getId()).heldId();
        ImportJob racing = reupload;
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> heldStatementService.createHold(
                racing, replayed, new com.finora.imports.trust.HoldDecision(true, List.of("forced")), "v1"));

        assertThat(heldStatementRepository.findByImportJobId(reupload.getId()))
                .as("no second review on rows %s already covers", adminHeldId).isEmpty();
        assertThat(refused).as("the pass is told which review covers it")
                .isInstanceOf(HeldStatementService.HoldCoveredException.class)
                .hasMessageContaining(adminHeldId);
    }

    /** What the worker leaves for that pass -- held, riding the covering review -- is decided with
     *  it, and a later re-upload follows the job that owns the review, not the rider. */
    @Test
    void aJobRidingAnotherReviewIsDecidedWithItAndReuploadsFollowTheOwner() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);
        String heldId = heldStatementService.openReviewForHoldWithoutRecord(user().getId(), held.getId()).heldId();
        ImportJob rider = new ImportJob(owner.getId(), "statement.csv", held.getContentHash(),
                held.getObjectKey(), "CSV", held.getEncryptionKeyId());
        rider.markClaimed("worker", Instant.now());
        rider.holdForTrustReview(held.getImportSessionId(), null, Instant.now());
        rider = jobRepository.save(rider);

        assertThat(upload(owner)).as("follows the job whose review is open").isEqualTo(held.getId());

        heldStatementService.approve(user().getId(), heldId, null, null);
        assertThat(jobRepository.findById(rider.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertConfirmable(owner, held.getImportSessionId());
    }

    /**
     * The lock itself, measured with two real transactions: while an operator's open-review
     * transaction holds the session, the worker's createHold waits for it -- and once it commits,
     * sees the review and refuses to write a second one.
     */
    @Test
    void createHoldWaitsForAConcurrentOpenReviewAndThenSeesIt() throws Exception {
        User owner = user();
        ImportJob held = heldWithNoReviewRecord(owner);
        ImportJob reupload = new ImportJob(owner.getId(), "statement.csv", held.getContentHash(),
                held.getObjectKey(), "CSV", held.getEncryptionKeyId());
        reupload.markClaimed("worker", Instant.now());
        ImportJob racing = jobRepository.save(reupload);
        StagedForJob replayed = new StagedForJob(held.getImportSessionId(), 2, 2, null, List.of(), List.of());
        UUID admin = user().getId();

        java.util.concurrent.CountDownLatch adminHoldsLock = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseAdmin = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<String> adminSide = pool.submit(() -> transactionTemplate.execute(status -> {
                String heldId = heldStatementService.openReviewForHoldWithoutRecord(admin, held.getId()).heldId();
                adminHoldsLock.countDown();
                try {
                    releaseAdmin.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return heldId;
            }));
            assertThat(adminHoldsLock.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<Throwable> workerSide = pool.submit(() ->
                    org.assertj.core.api.Assertions.catchThrowable(() -> heldStatementService.createHold(racing,
                            replayed, new com.finora.imports.trust.HoldDecision(true, List.of("forced")), "v1")));
            Thread.sleep(1500);
            assertThat(workerSide.isDone()).as("createHold waits on the operator's lock").isFalse();

            releaseAdmin.countDown();
            String adminHeldId = adminSide.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(workerSide.get(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(HeldStatementService.HoldCoveredException.class)
                    .hasMessageContaining(adminHeldId);
            assertThat(heldStatementRepository.findByImportJobId(racing.getId())).isEmpty();
        } finally {
            releaseAdmin.countDown();
            pool.shutdownNow();
        }
    }
}
