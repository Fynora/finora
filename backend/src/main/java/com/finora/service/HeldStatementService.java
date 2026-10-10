package com.finora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.HeldStatementDetailDto;
import com.finora.dto.HeldStatementDetailDto.EventView;
import com.finora.dto.HeldStatementDetailDto.FindingView;
import com.finora.dto.HeldStatementDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.StatementUpload;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.jobs.ParserVersionProvider;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.dto.HeldStatementRerunResultDto;
import com.finora.dto.HeldStatementStagedRowsDto;
import com.finora.dto.HoldWithoutReviewRecordDto;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.util.AfterCommit;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A held statement's whole life: opened by the worker when the trust predicate fires, and ended by
 * an operator deciding whether the extraction may reach the user's ledger.
 *
 * <p>{@code createHold} runs {@code REQUIRES_NEW} so the hold's own row and its first audit event
 * commit together and independently of the job's transaction. The worker treats a failure there as
 * non-fatal and holds the import anyway, so a half-written hold -- a row with no event, or an event
 * with no row -- would be worse than none: the operator queue would show a review with no history
 * explaining it.
 */
@Service
public class HeldStatementService {

    private static final Logger log = LoggerFactory.getLogger(HeldStatementService.class);

    /** The working queue: everything an operator can still act on. */
    private static final List<HeldStatement.Status> OPEN = List.of(
            HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
            HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT);

    private final HeldStatementRepository repository;
    private final HeldStatementEventRepository eventRepository;
    private final HeldStatementIdGenerator idGenerator;
    private final ImportJobRepository importJobRepository;
    private final ImportVerificationFindingRepository findingRepository;
    private final AuditService auditService;
    private final StatementStatusNotifier statementStatusNotifier;
    private final ImportSessionService importSessionService;
    private final ObjectMapper objectMapper;
    private final StatementContentService statementContentService;
    private final ImportService importService;
    private final ParserVersionProvider parserVersionProvider;
    private final HeldItemAdminAlertService heldItemAdminAlertService;
    private final com.finora.imports.passwords.StatementPasswordService statementPasswordService;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator,
                                ImportJobRepository importJobRepository,
                                ImportVerificationFindingRepository findingRepository,
                                AuditService auditService,
                                StatementStatusNotifier statementStatusNotifier,
                                ImportSessionService importSessionService,
                                ObjectMapper objectMapper,
                                StatementContentService statementContentService,
                                ImportService importService,
                                ParserVersionProvider parserVersionProvider,
                                HeldItemAdminAlertService heldItemAdminAlertService,
                                com.finora.imports.passwords.StatementPasswordService statementPasswordService) {
        this.statementPasswordService = statementPasswordService;
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
        this.importJobRepository = importJobRepository;
        this.findingRepository = findingRepository;
        this.auditService = auditService;
        this.statementStatusNotifier = statementStatusNotifier;
        this.importSessionService = importSessionService;
        this.objectMapper = objectMapper;
        this.statementContentService = statementContentService;
        this.importService = importService;
        this.parserVersionProvider = parserVersionProvider;
        this.heldItemAdminAlertService = heldItemAdminAlertService;
    }

    /**
     * Opens the review for one held import, or returns the review that already exists.
     *
     * <p>Idempotent on {@code import_job_id}, which is UNIQUE in V144, and that matters because
     * this commits in its own transaction: if the job's own transition then fails, the pass
     * retries and arrives here a second time for the same job. Inserting blindly would hit the
     * unique constraint, the worker would log it as a failed hold, and the import would end up
     * held with no review record -- despite a perfectly good one already sitting in the table.
     * Returning the existing hold keeps the retry harmless, and deliberately does NOT mint a
     * second Held ID: the operator is looking at one statement, not two.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HeldStatement createHold(ImportJob job, StagedForJob staged, HoldDecision decision,
                                    String parserVersion) {
        return repository.findByImportJobId(job.getId()).orElseGet(() -> {
            // Under the session's lock, the same one openReviewForHoldWithoutRecord takes: the
            // worker decided to hold a replay of these rows before this call, and an operator may
            // have opened a review on them since. Re-checked here, where it can no longer change.
            importSessionService.lockForReview(staged.sessionId());
            openReviewCovering(staged.sessionId(), job.getId()).ifPresent(covering -> {
                throw new HoldCoveredException(covering.getHeldId());
            });
            return openHold(job, staged, decision, parserVersion);
        });
    }

    /**
     * Where the review a worker job rides on stands now, read under the session's lock -- for the
     * worker's own final update of a job {@link #createHold} found covered
     * ({@link HoldCoveredException}). Joins the caller's transaction so the lock lasts until the
     * job's new status commits: approve and reject take the same lock before carrying riders with
     * them, so either the decision commits first and this reads it, or the job is held first and
     * the decision carries it. Without the lock a decision landing between the two would leave
     * the job held behind a review already decided.
     */
    @Transactional
    public Optional<HeldStatement.Status> settleRidingJob(UUID importSessionId, UUID jobId) {
        importSessionService.lockForReview(importSessionId);
        return priorReviewOf(importSessionId, jobId);
    }

    /**
     * {@link #createHold} found these rows already under another job's open review -- opened by an
     * operator between the worker deciding to hold and this call. The worker holds its job riding
     * that review ({@link #coveredBy}) instead of opening a second one; deciding the review decides
     * it. Not a failure, so the worker does not page for it.
     */
    public static class HoldCoveredException extends RuntimeException {
        private final String heldId;

        public HoldCoveredException(String heldId) {
            super("These rows are already under review in " + heldId);
            this.heldId = heldId;
        }

        public String heldId() { return heldId; }
    }

    /**
     * Where the trust review another upload opened on this staged session stands, if one did.
     *
     * <p>Staging replays a live session for the same bytes under the same build, so a later job can
     * arrive holding a session that has already been reviewed. Reviewing it again is wrong both
     * ways: {@code ImportSessionService.sessionsBlockedByTrustReview} keeps the session blocked
     * while ANY hold on it is not IMPORTED, so a second hold re-blocks a session an operator
     * already approved, and approving the second does nothing for a session an operator rejected.
     * The worker uses this to carry the existing decision over instead.
     *
     * <p>A job held with no review record -- the worker's fail-closed path when it could not write
     * one -- counts as an open review (HELD): its session is blocked, and nothing in the operator
     * queue can release it.
     *
     * <p>REJECTED wins over everything, then any unresolved status, then IMPORTED -- the same
     * order the blocking rule implies. {@code excludingJobId} is the caller's own job: a retried
     * pass may already have opened its own hold, and that one is not "another upload".
     */
    @Transactional(readOnly = true)
    public Optional<HeldStatement.Status> priorReviewOf(UUID importSessionId, UUID excludingJobId) {
        List<ImportJob> others = importJobRepository.findByImportSessionId(importSessionId).stream()
                .filter(other -> !other.getId().equals(excludingJobId))
                .toList();
        List<UUID> heldIds = others.stream()
                .map(ImportJob::getHeldStatementId)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<HeldStatement.Status> statuses = new java.util.ArrayList<>(repository.findAllById(heldIds).stream()
                .map(HeldStatement::getStatus)
                .toList());
        if (others.stream().anyMatch(HeldStatementService::isHeldWithNoRecord)) {
            statuses.add(HeldStatement.Status.HELD);
        }
        if (statuses.isEmpty()) return Optional.empty();
        if (statuses.contains(HeldStatement.Status.REJECTED)) return Optional.of(HeldStatement.Status.REJECTED);
        return statuses.stream().filter(status -> !status.isResolved()).findFirst()
                .or(() -> Optional.of(HeldStatement.Status.IMPORTED));
    }

    // --- holds opened by the synchronous stage endpoint --------------------------------------------

    /**
     * Where a trust review already covering this staged session stands, for the synchronous stage
     * endpoint: a re-upload there replays the live session for the same bytes, held or not
     * ({@code ImportSessionService.findLiveSessionByContentHash}), and must follow the review that
     * session already has rather than open a second one -- the same rule the worker applies to a
     * replay ({@link #priorReviewOf}).
     *
     * @return empty when no review covers the session; a review that blocks it with the job the
     *         client should follow; or an approved review ({@code jobId} null), which releases it
     */
    @Transactional(readOnly = true)
    public Optional<StagedSessionReview> reviewOfStagedSession(UUID importSessionId) {
        Optional<HeldStatement.Status> prior = priorReviewOf(importSessionId, null);
        if (prior.isEmpty()) return Optional.empty();
        if (prior.get() == HeldStatement.Status.IMPORTED) return Optional.of(new StagedSessionReview(null, prior.get()));
        // The job a user is told about: the one whose review an operator decides. A job riding it
        // with no record of its own follows that decision, so it is only the fallback.
        java.util.Comparator<ImportJob> preferred = java.util.Comparator
                .comparing((ImportJob job) -> job.getHeldStatementId() == null)
                .thenComparing(ImportJob::getCreatedAt, java.util.Comparator.reverseOrder());
        java.util.function.Predicate<ImportJob> carriesIt = prior.get() == HeldStatement.Status.REJECTED
                ? HeldStatementService::isRejectedByReview
                : job -> job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW;
        UUID jobId = importJobRepository.findByImportSessionId(importSessionId).stream()
                .filter(carriesIt)
                .sorted(preferred)
                .map(ImportJob::getId)
                .findFirst()
                .orElse(null);
        return Optional.of(new StagedSessionReview(jobId, prior.get()));
    }

    /** A trust review covering a staged session: the job carrying it (null once approved) and where
     *  the review stands. Only {@link HeldStatement.Status#IMPORTED} lets the rows be confirmed. */
    public record StagedSessionReview(UUID jobId, HeldStatement.Status status) {
        public boolean blocks() { return status != HeldStatement.Status.IMPORTED; }
    }

    /**
     * Holds a statement the synchronous stage endpoint staged and the trust predicate distrusts:
     * the same guarantee a queued upload gets from the worker. A job is created already held
     * ({@link ImportJob#heldOnStaging}), pointing at the staged session and at the stored copy of
     * the file the caller wrote, and its review record is opened through {@link #openHold} -- the
     * worker's own path, so the record, its first event, the user's "being checked" notification and
     * the admin alert are the ones every hold gets. The confirm gate then refuses the session on the
     * job's status ({@code ImportSessionService.sessionsBlockedByTrustReview}) until an operator
     * approves it.
     *
     * <p>Under the session's lock, the one {@link #createHold}, approve and reject take: two uploads
     * of the same bytes replay one session, and a worker job on those bytes may be holding it too.
     * Whichever gets the lock second sees the first's review and follows it rather than opening a
     * second one ({@link StagedHold#created} false). The lock also makes a confirm claim that arrives
     * meanwhile wait for this hold and then see it.
     *
     * @param lockedWithoutPassword the file is a password-protected PDF and no password is kept with
     *                              it, so the review is made from the staged rows (V258)
     */
    @Transactional
    public StagedHold holdStagedUpload(UUID userId, String fileName, String sourceFormat,
                                       com.finora.imports.storage.ContentAddress stored, String encryptionKeyId,
                                       StagedForJob staged, HoldDecision decision, String parserVersion,
                                       boolean lockedWithoutPassword) {
        importSessionService.lockForReview(staged.sessionId());
        Optional<StagedSessionReview> existing = reviewOfStagedSession(staged.sessionId());
        if (existing.isPresent()) {
            return new StagedHold(existing.get().blocks() ? existing.get().jobId() : null, false);
        }
        if (!importSessionService.exists(staged.sessionId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This upload's staged rows were removed before they could be checked. Upload the statement again.");
        }
        Instant now = Instant.now();
        ImportJob job = ImportJob.heldOnStaging(userId, fileName, stored.hash(), stored.key(), sourceFormat,
                encryptionKeyId, staged.sessionId(), now);
        job.recordProgress(staged.totalParsed(), staged.stagedRows());
        VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());
        job.recordVerificationTelemetry(
                telemetry.reliabilityStatus(), telemetry.textSource(),
                telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain(),
                telemetry.isEmpty() ? null : telemetry.findingsCount(),
                telemetry.isEmpty() ? null : telemetry.failedCount(),
                telemetry.isEmpty() ? null : telemetry.warningCount(),
                parserVersion);
        importJobRepository.saveAndFlush(job);

        HeldStatement held = openHold(job, staged, decision, parserVersion);
        if (lockedWithoutPassword) {
            held.markLockedWithoutPassword();
            repository.save(held);
        }
        job.attachReviewRecord(held.getId());
        importJobRepository.save(job);
        return new StagedHold(job.getId(), true);
    }

    /** What {@link #holdStagedUpload} did: the job the client follows while the rows are blocked
     *  (null when an approved review already released them), and whether it opened a new hold --
     *  when it did not, the stored copy the caller wrote for it is referenced by nothing. */
    public record StagedHold(UUID jobId, boolean created) {}

    // --- holds the worker could not write a record for ------------------------------------------

    /**
     * Imports held for trust review with no review record, oldest first. The worker holds an
     * import even when it cannot write the {@code held_statements} row (fail closed), and such a
     * hold blocks the user's confirm while appearing nowhere in the queue -- this is where an
     * operator finds it. {@code WorkerExecution.heldWithoutReviewRecord} pages when one is made.
     */
    @Transactional(readOnly = true)
    public PagedResponse<HoldWithoutReviewRecordDto> listHoldsWithoutReviewRecord(int page, int size) {
        Page<ImportJob> jobs = importJobRepository.findByStatusAndHeldStatementIdIsNull(
                ImportJob.Status.HELD_FOR_TRUST_REVIEW,
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "finishedAt")));
        return PagedResponse.of(jobs.map(job -> HoldWithoutReviewRecordDto.from(job,
                importSessionService.exists(job.getImportSessionId()),
                openReviewCovering(job).map(HeldStatement::getHeldId).orElse(null))));
    }

    /**
     * Another job's unresolved review on the same session, if one exists -- the review the worker
     * opened for a later upload of the statement, which this record-less job rides on
     * ({@link #coveredBy}). Opening a review of its own would put a second review on the same rows,
     * the duplicate the whole re-upload fix exists to stop.
     */
    private Optional<HeldStatement> openReviewCovering(ImportJob job) {
        return openReviewCovering(job.getImportSessionId(), job.getId());
    }

    /**
     * Whether another job's open review already covers this job's rows -- a re-upload replayed onto
     * a session under review, held with no record of its own and decided by that review. The
     * overdue escalation ({@code HoldOverdueEscalationService}) uses it so one review is escalated
     * once, not once per upload riding on it.
     *
     * <p>Deliberately not {@code @Transactional(readOnly = true)}: its caller marks the job inside
     * its own write transaction, and a read-only participant can leave that shared session on
     * manual flush, silently dropping the caller's write. Two plain reads need no transaction of
     * their own.
     */
    public boolean isCoveredByOpenReview(ImportJob job) {
        return openReviewCovering(job).isPresent();
    }

    private Optional<HeldStatement> openReviewCovering(UUID sessionId, UUID excludingJobId) {
        if (sessionId == null) return Optional.empty();
        List<UUID> others = importJobRepository.findByImportSessionId(sessionId).stream()
                .filter(other -> !other.getId().equals(excludingJobId))
                .map(ImportJob::getHeldStatementId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return repository.findAllById(others).stream()
                .filter(held -> !held.getStatus().isResolved())
                .findFirst();
    }

    /**
     * Writes the review record a held import never got, so it becomes an ordinary held statement:
     * in the queue, with its evidence, a parser re-run, and approve or reject like any other.
     * Deliberately not a release or reject of its own -- a decision about a customer's ledger is
     * made where the evidence is, and there is one approve and one reject to keep correct.
     *
     * <p>Goes through {@link #openHold}, the worker's own path, so the record, its first event, the
     * user's "being checked" notification (which the failed attempt never sent) and the admin alert
     * are the ones every hold gets. The trigger is read back from the staged session the worker
     * held, with the predicate anchored to the day it was held -- the same anchoring
     * {@link #rerunParser} uses. If those rows are gone, the review still opens, saying so; a
     * re-run stages the statement again.
     *
     * <p>Refused, too, when another job's open review already covers the same session: deciding
     * that review decides this job ({@link #coveredBy}), and a second review would block the rows
     * until both were approved.
     *
     * <p>Two operators opening the same one: the second sees the record the first wrote and gets a
     * 409; if both get past that read, {@code held_statements.import_job_id} is UNIQUE (V144) and
     * the loser's insert is refused, also as a 409.
     */
    @Transactional
    public HeldStatementDto openReviewForHoldWithoutRecord(UUID actingAdminId, UUID jobId) {
        ImportJob job = importJobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such import job."));
        if (!isHeldWithNoRecord(job)) {
            throw new ApiException(HttpStatus.CONFLICT, job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW
                    ? "This import already has a review record; open it from the held-statements queue."
                    : "This import is " + job.getStatus() + ", not held for review; there is nothing to open.");
        }
        // Under the session's lock before the check, so a worker pass holding a replay of these
        // rows cannot write its review in between (createHold takes the same lock).
        importSessionService.lockForReview(job.getImportSessionId());
        Optional<HeldStatement> covering = openReviewCovering(job);
        if (covering.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "This import's rows are already under review in "
                    + covering.get().getHeldId() + "; deciding that review decides this import too.");
        }

        Optional<com.finora.entity.ImportSession> session =
                importSessionService.findHeldSession(job.getImportSessionId());
        StagedForJob staged = session
                .map(s -> StagedForJob.of(importService.stagedResponseOf(job.getUserId(), s)))
                .orElseGet(() -> new StagedForJob(job.getImportSessionId(), 0, 0, null, List.of(), List.of()));
        java.time.LocalDate heldOn = (job.getFinishedAt() != null ? job.getFinishedAt() : Instant.now())
                .atZone(java.time.ZoneOffset.UTC).toLocalDate();
        HoldDecision evaluated = TrustPredicate.evaluate(staged.verificationReports(), staged.statementPeriods(), heldOn);
        List<String> reasons = new java.util.ArrayList<>();
        reasons.add(session.isPresent()
                ? "Held without a review record; review opened by an operator"
                : "Held without a review record, and its staged rows are gone; re-run the parser to read it again");
        reasons.addAll(evaluated.reasons());
        HoldDecision decision = new HoldDecision(true, reasons, evaluated.categories());

        HeldStatement held = openHold(job, staged, decision, job.getParserVersion());
        job.attachReviewRecord(held.getId());
        importJobRepository.save(job);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "REVIEW_RECORD_OPENED",
                null, held.getStatus().name(), null));
        auditService.record(actingAdminId, "TRUST_REVIEW_RECORD_OPENED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "importJobId", job.getId().toString(),
                        "stagedRowsAvailable", session.isPresent()));
        return HeldStatementDto.from(held);
    }

    private HeldStatement openHold(ImportJob job, StagedForJob staged, HoldDecision decision,
                                   String parserVersion) {
        VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());

        HeldStatement held = new HeldStatement(idGenerator.next(), job.getId(), job.getUserId(),
                job.getObjectKey(), decision.summary());
        // Snapshotted, not read live: a re-run under a later build has to be comparable against
        // what the build that produced this actually saw. isEmpty() distinguishes "nothing was
        // verified" from "verification found nothing", which are different facts about a hold.
        held.recordSnapshot(parserVersion,
                telemetry.reliabilityStatus() == null ? null : telemetry.reliabilityStatus().name(),
                telemetry.textSource(),
                telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain(),
                decision.categories().stream().map(Enum::name).toList());
        // staged.bankName() is already carried on StagedForJob for the completion notification --
        // see that record's own doc for why ImportJob can never learn the bank live.
        held.recordBank(staged.bankName());
        repository.save(held);

        // actorId null: the system opened this, not a person. The reasons are recorded here as
        // well as on the row because the row's summary is editable context for an operator, while
        // the event is the immutable record of what the predicate actually said at hold time.
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        // Same "we announce nothing on the way in" gap ImportJobWorker's parser-gap hold had, and
        // the same fix: a transactional-outbox write sharing this REQUIRES_NEW transaction, not a
        // real network call, so it belongs here rather than behind AfterCommit. See
        // ImportJobWorker.notifyHeldForReview's own doc for why one shared NotificationType and key
        // shape covers both hold reasons, and why a job cannot double-notify through this path --
        // createHold's own findByImportJobId short-circuit above means openHold, and this call,
        // only ever run once per job to begin with.
        statementStatusNotifier.notifyHeld(job);
        // Deferred until createHold's own REQUIRES_NEW transaction commits -- openHold is a plain
        // internal call from within that same method, not a separately-proxied one, so the
        // transaction is still active here and AfterCommit genuinely defers rather than running
        // immediately (contrast ImportJobWorker's parser-gap alert, whose own comment explains why
        // that call site is the other case). A real network call must not hold a pooled DB
        // connection across that wait, and must not fire for a hold that then rolled back.
        //
        // heldId extracted into a local first, not read as held.getHeldId() inside the lambda: the
        // lambda's captured state should be the bare id HeldItemAdminAlertService.alertTrustReviewHeld
        // re-reads by, not a reference that keeps the whole managed entity reachable for as long as
        // the registered TransactionSynchronization lives.
        String heldId = held.getHeldId();
        AfterCommit.run("held-item admin alert (trust review)",
                () -> heldItemAdminAlertService.alertTrustReviewHeld(heldId));
        return held;
    }

    // --- operator resolution ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<HeldStatementDto> list(int page, int size, HeldStatementFilter filter) {
        // Resolved here, not in the repository: JPQL arithmetic against CURRENT_TIMESTAMP is not
        // something every JPA provider evaluates the same way, and a fixed Instant computed once
        // per call is also what makes the query's own "older than" reasoning testable without a
        // clock dependency inside the query itself.
        Instant olderThan = filter.olderThanHours() == null
                ? null : Instant.now().minus(Duration.ofHours(filter.olderThanHours()));
        Page<HeldStatement> result = repository.findForAdmin(OPEN, filter.status(), filter.bankName(),
                olderThan, filter.assignedEngineerId(),
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
        return PagedResponse.of(result.map(HeldStatementDto::from));
    }

    /** One page of decided holds (imported or rejected), most recently decided first -- where a
     *  rejected hold is found again to {@link #reopen} it. Same filters as {@link #list}; a
     *  {@code status} filter naming an open status matches nothing here. */
    @Transactional(readOnly = true)
    public PagedResponse<HeldStatementDto> listResolved(int page, int size, HeldStatementFilter filter) {
        Instant olderThan = filter.olderThanHours() == null
                ? null : Instant.now().minus(Duration.ofHours(filter.olderThanHours()));
        Page<HeldStatement> result = repository.findResolvedForAdmin(HeldStatement.Status.RESOLVED,
                filter.status(), filter.bankName(), olderThan, filter.assignedEngineerId(),
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25)));
        return PagedResponse.of(result.map(HeldStatementDto::from));
    }

    /**
     * The summary plus the evidence behind {@code triggerSummary} and the hold's own history.
     *
     * <p>Findings come from {@code import_verification_findings}, keyed by {@code import_job_id} --
     * the same table and the same allowlisted, statement-content-free shape {@code
     * ImportTraceService} already exposes to engineers diagnosing a parser failure. This is not a
     * new signal: it is the printed-versus-parsed evidence the trust predicate itself read to
     * decide to hold, surfaced as the numbers rather than as a sentence about them.
     */
    @Transactional(readOnly = true)
    public HeldStatementDetailDto detail(String heldId) {
        HeldStatement held = require(heldId);
        List<ImportVerificationFinding> rows =
                findingRepository.findByImportJobIdOrderBySectionIndexAscRuleAsc(held.getImportJobId());
        List<HeldStatementEvent> events =
                eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
        // Optional, not requireJob: this is a read-only view and must still render for the one
        // case requireJob's own doc names -- a job deleted out from under an open review -- rather
        // than turning an otherwise-fine detail page into a 409 over a field the page barely uses.
        String fileName = importJobRepository.findById(held.getImportJobId())
                .map(ImportJob::getFileName).orElse(null);
        return new HeldStatementDetailDto(HeldStatementDto.from(held), fileName, findings(rows), timeline(events));
    }

    private List<FindingView> findings(List<ImportVerificationFinding> rows) {
        return rows.stream()
                .map(row -> new FindingView(row.getSectionIndex(), row.getRule(), row.getOutcome(),
                        readDetails(row), row.getCreatedAt()))
                .toList();
    }

    /** Unreadable details degrade one field rather than failing the whole detail view -- same call
     *  {@code ImportTraceService.readDetails} makes about a row a future version wrote in a shape
     *  this one does not expect. */
    private Map<String, Object> readDetails(ImportVerificationFinding row) {
        String json = row.getDetailsJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Held statement finding {} has unreadable details; reporting the rest of the "
                    + "finding without them", row.getId(), e);
            return Map.of();
        }
    }

    private List<EventView> timeline(List<HeldStatementEvent> events) {
        return events.stream()
                .map(e -> new EventView(e.getEventType(), e.getFromStatus(), e.getToStatus(),
                        e.getNotes(), e.getActorId(), e.getCreatedAt()))
                .toList();
    }

    // --- assignment (brief Phase 6, pulled forward by the owner's decision, 2026-09-04) -----------

    /**
     * Assigns the hold to an engineer -- {@code engineerId} null means "Assign to Me", the common
     * case, which must not require typing an id.
     *
     * <p>The entity's own {@link HeldStatement#assign} already allows reassigning an unresolved
     * hold (that guard and its test predate this task); this is the service, endpoint and audit
     * around it, not a new state-machine rule.
     */
    @Transactional
    public HeldStatementDto assign(UUID actingAdminId, String heldId, UUID engineerId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "assigned");

        HeldStatement.Status from = held.getStatus();
        UUID assignee = engineerId != null ? engineerId : actingAdminId;
        held.assign(assignee, Instant.now());
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "ASSIGNED",
                from.name(), held.getStatus().name(), null));
        auditService.record(actingAdminId, "TRUST_REVIEW_ASSIGNED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "assignedTo", assignee.toString()));
        return HeldStatementDto.from(held);
    }

    /**
     * Moves a hold into active investigation.
     *
     * <p>The entity's own {@code startInvestigation} carries no source-status guard beyond {@code
     * refuseIfResolved} -- Plan 1's own test only exercises it after {@code assign}, but nothing
     * stops calling this on a HELD row that was never assigned first, and this does not invent a
     * restriction the entity's state machine does not have. An operator who can already see the
     * extraction going straight to investigating it, without a separate assignment step, is a
     * legitimate way to work the queue, not a gap.
     */
    @Transactional
    public HeldStatementDto startInvestigation(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "moved back into investigation");

        HeldStatement.Status from = held.getStatus();
        held.startInvestigation();
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "INVESTIGATING",
                from.name(), held.getStatus().name(), null));
        auditService.record(actingAdminId, "TRUST_REVIEW_INVESTIGATION_STARTED", "HeldStatement",
                held.getId(), Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    /**
     * Replaces the engineer's write-up wholesale, same as {@link HeldStatement#addNotes} itself
     * documents -- the history of what it said before lives in the event this writes, not in a
     * second notes column.
     *
     * <p>Deliberately not guarded by {@code refuseIfResolved}: the entity's own {@code addNotes}
     * carries no such guard, and a closing note explaining the final reasoning after a decision is
     * a legitimate thing to record, not a state-machine violation to prevent.
     */
    @Transactional
    public HeldStatementDto addNotes(UUID actingAdminId, String heldId, String notes) {
        HeldStatement held = require(heldId);
        held.addNotes(notes);
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "NOTES_UPDATED",
                null, null, notes));
        auditService.record(actingAdminId, "TRUST_REVIEW_NOTES_UPDATED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    /** Records what an engineer found and where the fix landed. Same replace-wholesale semantics
     *  as {@link #addNotes}, and deliberately not guarded by {@code refuseIfResolved} for the
     *  identical reason. */
    @Transactional
    public HeldStatementDto recordFindings(UUID actingAdminId, String heldId, String rootCause,
                                           String fixReference) {
        HeldStatement held = require(heldId);
        held.recordEngineerFindings(rootCause, fixReference);
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "FINDINGS_UPDATED",
                null, null, rootCause));
        auditService.record(actingAdminId, "TRUST_REVIEW_FINDINGS_UPDATED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    /**
     * Fyn Phase 2 (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6 Phase 2).
     * Called by {@code FynImportDiagnosisService} after a successful Anthropic call -- this method
     * itself does not touch {@code AiAuditLogRepository}, {@code FynAvailabilityGuard}, or Claude;
     * it only persists the result, exactly like {@link #addNotes}/{@link #recordFindings} persist
     * an engineer's own write-up. Same replace-wholesale semantics, same "not guarded by {@code
     * refuseIfResolved}" reasoning, and a separate column rather than {@code engineerNotes} --
     * see {@code HeldStatement.recordAiSuggestion}'s own doc for why.
     */
    @Transactional
    public HeldStatementDto recordAiSuggestion(UUID actingAdminId, String heldId, String diagnosis) {
        HeldStatement held = require(heldId);
        Instant now = Instant.now();
        held.recordAiSuggestion(diagnosis, now);
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId,
                "AI_DIAGNOSIS_SUGGESTED", null, null, diagnosis));
        auditService.record(actingAdminId, "TRUST_REVIEW_AI_DIAGNOSIS_SUGGESTED", "HeldStatement",
                held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        return HeldStatementDto.from(held);
    }

    private static final String PARSER_RERUN_EVENT = "PARSER_RERUN";

    /**
     * Re-parses this hold's original bytes with the CURRENT parser build and reports whether the
     * trust predicate would still flag it.
     *
     * <p>Parses through {@link ImportService#reparse}, never the live staging path -- that one
     * would delete the {@code ImportSession} a later {@link #approve} still needs whatever the
     * verdict. Only a run that clears replaces it: its own parse is staged in place of the rows
     * staged when held, so approving releases this build's reading, not the one that was held.
     *
     * <p>{@code today} is {@code held.getCreatedAt()}'s date, not the date this method runs on --
     * using the current date would let a genuinely future-dated statement period stop being
     * flagged for no reason but calendar drift, which would misreport a rerun as having fixed
     * something no parser change touched.
     *
     * <p>Writes exactly one thing beyond the hold's own status: a {@code PARSER_RERUN} event. It
     * never calls {@code ImportVerificationRecorder.recordForJob} -- {@code
     * ImportVerificationFinding} rows are immutable and carry no attempt/version column, so a
     * second write against the same {@code importJobId} would sit indistinguishably beside the
     * original hold's evidence in {@link #detail}.
     *
     * <p>Clearing moves the hold to {@code READY_FOR_IMPORT}, never straight to {@code IMPORTED}
     * -- a human still approves. Calling this again on an already-{@code READY_FOR_IMPORT} hold is
     * legal and idempotent: {@code HeldStatement.markReadyForImport}'s only guard is {@code
     * refuseIfResolved}, which does not single out a required starting status.
     *
     * <p>The clearing path is protected against a concurrent {@link #approve}/{@link #reject}/etc.
     * on the same hold by {@code HeldStatement}'s {@code @Version} column (V151): a losing
     * concurrent write there throws {@code ObjectOptimisticLockingFailureException}, mapped to a
     * 409 by {@code GlobalExceptionHandler.handleOptimisticLock} -- never a silent overwrite of
     * whichever admin action committed first. <b>The still-held path is not equally protected</b>:
     * when {@code decision.hold()} stays true, {@code held} is never re-saved (nothing about it
     * changed), so no version check fires. If a concurrent resolution wins a genuine race against
     * this method's own stale-in-transaction read, the {@code PARSER_RERUN} event this branch
     * writes can trail the resolution -- recording {@code from}/{@code to} as the hold's
     * pre-resolution status even though the row is by then already resolved. This does not corrupt
     * the hold's actual status (this branch never writes one), only its own event's historical
     * accuracy; a narrow, low-severity gap left open rather than pulling in
     * {@code EntityManager.lock(..., LockModeType.OPTIMISTIC_FORCE_INCREMENT)} for a race this
     * narrow.
     */
    @Transactional
    public HeldStatementRerunResultDto rerunParser(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "re-parsed");
        refuseIfLockedWithoutPassword(held, "re-parsed");
        ImportJob job = requireJob(held);
        refuseIfUploadedAgain(held, job, "re-parsed");

        byte[] content = readStatement(held, job);
        ImportService.ReparsedStatement reparsed = null;
        ImportService.DryRunResult dryRun;
        String extractionError = null;
        try {
            // A locked upload gets here only with a password the user saved (step 4): one staged
            // without a kept password was refused above.
            reparsed = importService.reparse(job.getUserId(), job.getFileName(), content, job.getSourceFormat(),
                    statementPasswordService.forJob(job.getId()).orElse(null));
            dryRun = reparsed.dryRun();
        } catch (ApiException e) {
            dryRun = new ImportService.DryRunResult(List.of(), List.of());
            String code = e.getCode() != null ? e.getCode().name() : "UNKNOWN";
            extractionError = code + ": " + e.getMessage();
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not re-read this statement: " + e.getMessage());
        }

        java.time.LocalDate anchoredToday = held.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        HoldDecision decision = extractionError != null
                ? new HoldDecision(true, List.of("Current parser build fails to extract this document ("
                        + extractionError + ")"))
                : TrustPredicate.evaluate(dryRun.verificationReports(), dryRun.statementPeriods(), anchoredToday);

        String previousVersion = held.getParserVersion();
        String currentVersion = parserVersionProvider.current();
        boolean versionChanged = currentVersion != null && !currentVersion.equals(previousVersion);
        HeldStatement.Status from = held.getStatus();
        if (!decision.hold()) {
            // The rows approving releases are this build's reading, not the ones staged when the
            // statement was held: those came from the parser whose output held it, and releasing
            // them after a re-run that cleared would hand the user exactly what was wrong. The
            // parse just made is staged in their place -- once, so a scanned statement's OCR is not
            // run twice. The old session goes first: one live session per user and document (V79).
            // A job held on the same session with no review record moves with it: left on the
            // discarded session it would stay held on rows that no longer exist, and approving
            // would look for it on the new session and miss it.
            List<ImportJob> riding = coveredBy(job, HeldStatementService::isHeldWithNoRecord);
            importSessionService.discardForRestage(job.getImportSessionId());
            ImportService.StagedReparse staged =
                    importService.stageReparsed(job.getUserId(), job.getFileName(), content, reparsed);
            job.replaceHeldSession(staged.sessionId(), staged.totalParsed(), staged.stagedRows());
            importJobRepository.save(job);
            riding.forEach(other -> other.replaceHeldSession(
                    staged.sessionId(), staged.totalParsed(), staged.stagedRows()));
            importJobRepository.saveAll(riding);
            held.markReadyForImport(Instant.now());
            repository.save(held);
        }

        String summaryNote = (decision.hold()
                ? "Still held: " + String.join("; ", decision.reasons())
                : "Clears under the current parser build; its rows replace the ones staged when held.")
                + " Parser version: " + previousVersion + " -> " + currentVersion
                + " (" + (versionChanged ? "changed" : "unchanged") + ").";
        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, PARSER_RERUN_EVENT,
                from.name(), held.getStatus().name(), summaryNote));
        auditService.record(actingAdminId, "TRUST_REVIEW_PARSER_RERUN", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "stillHeld", decision.hold(),
                        "previousParserVersion", String.valueOf(previousVersion),
                        "currentParserVersion", String.valueOf(currentVersion),
                        "parserVersionChanged", versionChanged));

        return new HeldStatementRerunResultDto(previousVersion, currentVersion, versionChanged,
                decision.hold(), decision.reasons(), HeldStatementDto.from(held));
    }

    /**
     * Releases the hold: the staged rows may now reach the user's confirm step.
     *
     * <p>The notification is not optional politeness. The held-state copy the user is shown says,
     * in as many words, "we'll notify you once it's ready" -- and the worker's own
     * {@code notifyIfPreviouslyHeld} cannot cover this, because it gates on {@code
     * wasHeldForReview}, which a trust hold deliberately never sets. Without this the promise would
     * simply not be kept: the import would quietly become available and nobody would be told.
     *
     * <p>The bank name is not available here. {@code ImportJob} never learns it -- see
     * {@code StagedForJob}'s own doc -- so this uses the template's documented fallback, giving
     * "Your bank statement is ready". Loading the session to recover the name would be a database
     * round trip to improve one word.
     */
    @Transactional
    public HeldStatementDto approve(UUID actingAdminId, String heldId, String note, Boolean falsePositive) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "approved");

        ImportJob job = requireJob(held);
        // Before anything is decided, so a worker job riding this review is either already held
        // (and carried below) or settles against the decision once it commits -- settleRidingJob.
        importSessionService.lockForReview(job.getImportSessionId());
        refuseIfUploadedAgain(held, job, "approved");
        // A rejected-then-reopened hold's session may have been swept while its job was failed;
        // releasing it would send the user to rows that no longer exist.
        if (!importSessionService.exists(job.getImportSessionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "The staged rows behind " + held.getHeldId()
                    + " are gone. Re-run the parser to read the statement again before approving.");
        }
        Instant now = Instant.now();
        HeldStatement.Status from = held.getStatus();

        held.markImported(actingAdminId, now, falsePositive);
        job.releaseAfterTrustReview(now);
        repository.save(held);
        importJobRepository.save(job);
        List<UUID> covered = resolveHoldsWithNoRecord(job, other -> other.releaseAfterTrustReview(now));

        String eventNote = (falsePositive != null && falsePositive)
                ? (note == null || note.isBlank() ? "Marked false positive." : note + " (marked false positive)")
                : note;
        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "APPROVED",
                from.name(), held.getStatus().name(), eventNote));
        auditService.record(actingAdminId, "TRUST_REVIEW_APPROVED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        // Map.of rejects nulls, and an operator is not required to explain
                        // themselves -- the empty string keeps the entry writable either way.
                        "note", note == null ? "" : note,
                        "falsePositive", falsePositive == null ? "unmarked" : falsePositive.toString(),
                        "coveredJobIds", covered.toString()));

        // Before the notification, deliberately. The sweep's exemption lifts the moment this job
        // leaves HELD_FOR_TRUST_REVIEW, and the session's expiresAt is still whatever staging set
        // it to -- long elapsed for any review worth holding for. Telling the user their statement
        // is ready and letting the next sweep delete it minutes later is the same broken promise,
        // moved. They have not seen these rows yet; the wait was ours.
        importSessionService.renewExpiry(job.getImportSessionId());

        notifyStatementReady(job);
        return HeldStatementDto.from(held);
    }

    /**
     * Ends the review the other way: these rows never reach the ledger.
     *
     * <p>The user is told, by push and email. The held email promised "We'll notify you once it's
     * ready", and a rejection is the other answer to that wait: without a notification the user only
     * learned of it by opening the app. Same reasoning, and the same fix, as the parser-gap hold's
     * resolve (V216). The copy is fixed -- the operator's reason is internal -- and matches the
     * failure the user's progress screen shows from {@code IMPORT_TRUST_REVIEW_REJECTED}.
     * Not sent when the user has since uploaded the statement again ({@link #uploadedAgain}).
     *
     * <p>The operator's reason goes on the audit entry and the event, never onto the row's
     * {@code engineerNotes}: there is one notes column, and overwriting it here would destroy the
     * investigation findings the rejection was based on.
     */
    @Transactional
    public HeldStatementDto reject(UUID actingAdminId, String heldId, String reason) {
        HeldStatement held = require(heldId);
        refuseIfResolved(held, "rejected");

        ImportJob job = requireJob(held);
        importSessionService.lockForReview(job.getImportSessionId()); // as in approve
        // Read before the job fails: a superseded hold is the one the re-upload guards tell the
        // operator to reject, and its user has already moved on with their own upload.
        String uploadedAgain = uploadedAgain(job);
        Instant now = Instant.now();
        HeldStatement.Status from = held.getStatus();

        held.reject(actingAdminId, now);
        job.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now);
        repository.save(held);
        importJobRepository.save(job);
        List<UUID> covered = resolveHoldsWithNoRecord(job,
                other -> other.rejectAfterTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now));

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "REJECTED",
                from.name(), held.getStatus().name(), reason));
        auditService.record(actingAdminId, "TRUST_REVIEW_REJECTED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "reason", reason == null ? "" : reason,
                        "coveredJobIds", covered.toString(),
                        "userNotified", uploadedAgain == null));
        // An outbox write in this transaction, like notifyStatementReady in approve: a rejection
        // that rolls back tells nobody. Not when the user has uploaded the statement again: "your
        // statement wasn't imported, nothing was added" would be wrong about the copy they have
        // imported or are importing now.
        if (uploadedAgain == null) {
            statementStatusNotifier.notifyRejected(job);
        }
        return HeldStatementDto.from(held);
    }

    /**
     * Takes back a rejection so the statement can be re-read once its parser fix ships: the hold
     * returns to {@code INVESTIGATING} and the import to held, so the user sees "running
     * additional checks" again rather than a failure. Re-run the parser, then approve, as for any
     * open hold -- approving straight away is refused if the staged rows were swept meanwhile.
     *
     * <p>Refused when the user has since uploaded the statement again ({@link #refuseIfUploadedAgain}),
     * and for a locked PDF whose saved password the hourly sweep has already deleted: neither hold
     * could be carried to an import, so reopening one would only take it off the resolved list.
     */
    @Transactional
    public HeldStatementDto reopen(UUID actingAdminId, String heldId, String reason) {
        HeldStatement held = require(heldId);
        if (held.getStatus() != HeldStatement.Status.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    held.getHeldId() + " is " + held.getStatus() + "; only a rejected hold can be reopened.");
        }
        ImportJob job = requireJob(held);
        if (job.getStatus() != ImportJob.Status.FAILED
                || !ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name().equals(job.getFailureCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "The import behind " + held.getHeldId()
                    + " is " + job.getStatus() + ", not the rejection this would undo.");
        }
        refuseIfUploadedAgain(held, job, "reopened");
        // A locked statement reached the queue with the password its user saved, and the hourly
        // sweep deletes a failed job's password (deleteUnusableJobPasswords) -- or through the
        // synchronous stage endpoint with no password kept at all (lockedWithoutPassword). Either
        // way nothing can read the file again, so reopening would only strand the hold.
        if (StatementUpload.Format.PDF.name().equals(job.getSourceFormat())
                && !statementPasswordService.hasJobPassword(job.getId())
                && PdfTextExtractor.needsPassword(new java.io.ByteArrayInputStream(readStatement(held, job)))) {
            throw new ApiException(HttpStatus.CONFLICT, held.getHeldId() + " cannot be reopened: the statement "
                    + "is password-protected and " + (held.isLockedWithoutPassword()
                            ? "its password was never kept"
                            : "the password saved with it was deleted after the rejection")
                    + ", so it cannot be read again. Ask the user to upload it again.");
        }

        HeldStatement.Status from = held.getStatus();
        Instant now = Instant.now();
        held.reopen();
        job.reopenTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now);
        repository.save(held);
        importJobRepository.save(job);
        // The jobs reject() failed alongside this one, because they had no review of their own,
        // are held again with it -- otherwise approving the reopened review would leave them failed
        // for rows that reached the ledger.
        List<ImportJob> riding = coveredBy(job, HeldStatementService::isRejectedWithNoRecord);
        riding.forEach(other -> other.reopenTrustReview(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name(), now));
        importJobRepository.saveAll(riding);

        eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "REOPENED",
                from.name(), held.getStatus().name(), reason));
        auditService.record(actingAdminId, "TRUST_REVIEW_REOPENED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "reason", reason == null ? "" : reason));
        return HeldStatementDto.from(held);
    }

    /**
     * The rows this hold staged -- what approving releases, and the whole review when the file
     * itself cannot be opened ({@link HeldStatement#isLockedWithoutPassword()}).
     *
     * <p>Statement content, so it is audited like {@link #download}: before the rows are read, in a
     * transaction of its own so the refusal below does not roll the record back. The controller pins
     * it to the same roles as the document.
     *
     * <p>409 when the rows are gone -- a rejected-then-reopened hold whose session was swept -- with
     * the way back that applies: a re-run for a file the parser can read, a new upload otherwise.
     */
    @Transactional
    public HeldStatementStagedRowsDto stagedRows(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        ImportJob job = requireJob(held);
        auditService.recordEvenOnRollback(actingAdminId, "TRUST_REVIEW_STAGED_ROWS_VIEWED", "HeldStatement", held.getId(),
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId()));
        com.finora.entity.ImportSession session = importSessionService.findHeldSession(job.getImportSessionId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "The staged rows behind "
                        + held.getHeldId() + " are gone. " + (held.isLockedWithoutPassword()
                                ? "The statement is password-protected and its password was not kept, so it "
                                        + "cannot be read again; ask the user to upload it again."
                                : "Re-run the parser to read the statement again.")));
        var staged = importService.stagedResponseOf(job.getUserId(), session);
        List<HeldStatementStagedRowsDto.HeldSection> sections = staged.multiAccount()
                ? (staged.sections() == null ? List.<HeldStatementStagedRowsDto.HeldSection>of()
                        : staged.sections().stream()
                                .map(section -> sectionOf(section.detectedAccount(), section.totalParsed(), section.rows()))
                                .toList())
                : staged.staging() == null ? List.of()
                        : List.of(sectionOf(staged.staging().detectedAccount(), staged.staging().totalParsed(),
                                staged.staging().rows()));
        return new HeldStatementStagedRowsDto(held.getHeldId(), sections);
    }

    private static HeldStatementStagedRowsDto.HeldSection sectionOf(
            com.finora.dto.ImportDto.DetectedAccountInfo account, int totalParsed,
            List<com.finora.dto.ImportDto.StagedRow> rows) {
        List<HeldStatementStagedRowsDto.HeldRow> view = rows == null ? List.of() : rows.stream()
                .map(row -> new HeldStatementStagedRowsDto.HeldRow(row.date(), row.description(), row.amount(),
                        row.type(), row.balanceAfter(), row.referenceNumber()))
                .toList();
        return account == null
                ? new HeldStatementStagedRowsDto.HeldSection(null, null, null, null, null, null, null, totalParsed, view)
                : new HeldStatementStagedRowsDto.HeldSection(account.suggestedName(), account.accountNumberMasked(),
                        account.suggestedAccountType(), account.statementPeriodStart(), account.statementPeriodEnd(),
                        account.openingBalance(), account.closingBalance(), totalParsed, view);
    }

    /** What the download endpoint hands back -- everything the controller needs to set the
     *  response headers, in one value. Same shape as {@code StatementImportService.FileDownload},
     *  for the same reason. */
    public record DownloadedStatement(String fileName, byte[] content, String contentType) {}

    /**
     * The one place in the product that hands a customer's bank statement to a member of staff.
     *
     * <p>Audited BEFORE the bytes are read, not after -- a failed transfer (a storage outage, a
     * decrypt failure) must still leave a record that the attempt was made, since the attempt
     * itself is the sensitive event, not only a successful one. The role gate that makes this safe
     * to expose at all lives on the controller, one layer up: {@code TRUST_REVIEW_MANAGE} alone
     * would let anyone who can work the queue reach this method, and that permission is grantable
     * to a future support role that must never receive a customer's statement -- see the
     * repository owner's decision, 2026-09-04, in the Plan 2 document.
     *
     * <p>Deliberately plain {@code @Transactional}, not {@code readOnly = true}, even though the
     * method never mutates a row -- it writes one, the audit entry. {@code AdminNotificationService
     * .detail}'s own doc already caught this exact bug once: a read-only transaction sets
     * Hibernate's flush mode to {@code MANUAL}, so the audit row registered via {@code
     * auditLogRepository.save()} is silently never flushed to the database -- no exception, the
     * row just never existed. Caught here by {@code everyDownloadIsAudited} actually querying
     * Postgres for the row rather than asserting a mock was called.
     */
    @Transactional
    public DownloadedStatement download(UUID actingAdminId, String heldId) {
        HeldStatement held = require(heldId);
        // Before the audit entry: nothing is handed over. The stored file is still locked, and a
        // copy nobody can open is a customer's statement given to staff for no purpose.
        refuseIfLockedWithoutPassword(held, "downloaded");
        ImportJob job = requireJob(held);

        // Recorded before the bytes are read (see above), so it can only say a saved password exists
        // and an unlocked copy will be attempted -- reviewCopy falls back to the stored file.
        boolean savedPassword = "PDF".equalsIgnoreCase(job.getSourceFormat())
                && statementPasswordService.hasJobPassword(job.getId());
        // Its own transaction: the 409s below (a missing file, an unreadable one) roll this one
        // back, and the attempt is the event being recorded.
        auditService.recordEvenOnRollback(actingAdminId, "TRUST_REVIEW_DOCUMENT_DOWNLOADED", "HeldStatement",
                held.getId(), Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", held.getUserId().toString(),
                        "heldId", held.getHeldId(),
                        "savedPasswordOnFile", savedPassword));

        byte[] content = readStatement(held, job);
        if (savedPassword) {
            // A protected PDF whose password the user saved is unlocked in memory for this download
            // only, so the reviewer can read it; no unlocked copy is stored.
            content = statementPasswordService.reviewCopy(job, content).content();
        }
        return new DownloadedStatement(job.getFileName(), content, contentTypeFor(job.getSourceFormat()));
    }

    /** Same switch {@code StatementImportService.contentTypeFor} makes, over the formats this
     *  system actually stores -- not a filename-extension lookup, which is attacker-influenced. */
    private static String contentTypeFor(String sourceFormat) {
        if (sourceFormat == null) return "application/octet-stream";
        return switch (sourceFormat.toUpperCase()) {
            case "CSV" -> "text/csv";
            case "PDF" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    // --- internals -------------------------------------------------------------------------------

    private static boolean isHeldWithNoRecord(ImportJob job) {
        return job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW && job.getHeldStatementId() == null;
    }

    /**
     * Applies this review's decision to any job held on the same session with no review record.
     *
     * <p>Such a job blocks the session on its own status (see {@code
     * ImportSessionService.sessionsBlockedByTrustReview}) and has nothing an operator can decide,
     * so it can only be released by the review that covers its rows -- the one the worker opens for
     * the re-upload that replayed them. Without this, approving that review would leave the session
     * blocked behind a job nobody can reach.
     *
     * @return the ids of the jobs it moved, for the audit entry
     */
    private List<UUID> resolveHoldsWithNoRecord(ImportJob decided, java.util.function.Consumer<ImportJob> decision) {
        List<ImportJob> covered = coveredBy(decided, HeldStatementService::isHeldWithNoRecord);
        covered.forEach(decision);
        importJobRepository.saveAll(covered);
        return covered.stream().map(ImportJob::getId).toList();
    }

    /**
     * The jobs with no review record of their own that ride on {@code reviewed}'s review: same
     * session, {@code heldStatementId} null, in the given state. They follow every move the review
     * makes -- decided by approve and reject ({@link #resolveHoldsWithNoRecord}), re-pointed when a
     * re-run replaces the session, and held again when a rejection is reopened -- or they are left
     * behind on a job status nothing will ever change.
     */
    private List<ImportJob> coveredBy(ImportJob reviewed, java.util.function.Predicate<ImportJob> state) {
        if (reviewed.getImportSessionId() == null) return List.of();
        return importJobRepository.findByImportSessionId(reviewed.getImportSessionId()).stream()
                .filter(other -> !other.getId().equals(reviewed.getId()))
                .filter(other -> other.getHeldStatementId() == null)
                .filter(state)
                .toList();
    }

    /** What {@link #reject} did to a job riding on the review: failed with the review's own code. */
    private static boolean isRejectedByReview(ImportJob job) {
        return job.getStatus() == ImportJob.Status.FAILED
                && ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name().equals(job.getFailureCode());
    }

    private static boolean isRejectedWithNoRecord(ImportJob job) {
        return job.getStatus() == ImportJob.Status.FAILED
                && ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name().equals(job.getFailureCode());
    }

    private HeldStatement require(String heldId) {
        return repository.findByHeldId(heldId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such held statement."));
    }

    /**
     * The job the hold is about. Its absence is a 409 rather than a 500 because the only way it
     * happens is a job deleted underneath a review -- a state an operator can understand and
     * nothing here can fix.
     */
    private ImportJob requireJob(HeldStatement held) {
        return importJobRepository.findById(held.getImportJobId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "The import behind " + held.getHeldId() + " no longer exists."));
    }

    /** The held upload's stored file. A missing or unreadable object is a 409 naming the hold
     *  rather than a 500: nothing about the request was wrong, and the reviewer needs to know the
     *  file itself is what is gone. */
    private byte[] readStatement(HeldStatement held, ImportJob job) {
        try {
            return statementContentService.read(job);
        } catch (com.finora.imports.storage.StatementStorageException e) {
            log.warn("Stored statement behind {} could not be read", held.getHeldId(), e);
            throw new ApiException(HttpStatus.CONFLICT,
                    "The stored statement file behind " + held.getHeldId() + " could not be read.");
        }
    }

    /**
     * Refuses to carry a hold forward when the user has since uploaded the same statement again
     * themselves -- a hold does not stop them (V134/V144 keep held jobs out of the live-content
     * index), and a rejection tells them it failed. Carrying the hold on would hand them a second
     * copy of a statement they already imported, or collide with the upload they are reviewing:
     * that upload's session holds the one V79 slot for this document, so staging the re-read
     * would fail on the constraint. Checked before any parse, so nothing is spent on a refusal.
     */
    private void refuseIfUploadedAgain(HeldStatement held, ImportJob job, String verb) {
        String uploadedAgain = uploadedAgain(job);
        if (uploadedAgain != null) {
            String instead = held.getStatus() == HeldStatement.Status.REJECTED
                    ? " Leave it rejected." : " Reject it instead.";
            throw new ApiException(HttpStatus.CONFLICT,
                    held.getHeldId() + " cannot be " + verb + ": the user " + uploadedAgain + instead);
        }
    }

    /**
     * How the user has taken this statement up again themselves since this hold's upload, or null
     * if they have not: imported it again, another upload of it still running, or another upload's
     * rows still staged. Phrased to follow "the user ".
     */
    private String uploadedAgain(ImportJob job) {
        String hash = job.getContentHash();
        if (hash == null) return null;
        var imported = importService.previousImportOf(job.getUserId(), hash);
        if (imported != null && imported.importedAt() != null && imported.importedAt().isAfter(job.getCreatedAt())) {
            return "imported this statement again on " + imported.importedAt() + ", so it would reach them twice.";
        }
        boolean inFlight = importJobRepository
                .findFirstByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                        job.getUserId(), hash, ImportJob.Status.TERMINAL)
                .filter(other -> !other.getId().equals(job.getId()))
                .isPresent();
        if (inFlight) {
            return "has uploaded this statement again and that import is still running.";
        }
        if (importSessionService.stagedSessionOfAnotherUpload(job.getUserId(), hash, job.getImportSessionId())
                .isPresent()) {
            return "has uploaded this statement again, and that upload's rows are still staged.";
        }
        return null;
    }

    /**
     * 409 naming the state, the same convention {@code AdminHeldImportService.reprocess} uses, so
     * an operator can tell "someone already decided this" from "this cannot be decided".
     *
     * <p>Checked here as well as in the entity so a double-clicked button gets an explainable
     * conflict rather than a 500 from an IllegalStateException.
     */
    /**
     * A locked PDF staged through the synchronous endpoint, which keeps no password (V258): the
     * stored file cannot be opened, so it cannot be read by a reviewer or by the parser. 409 naming
     * what the review is made from instead, rather than a re-run that fails on the password or a
     * download nobody can open.
     */
    private static void refuseIfLockedWithoutPassword(HeldStatement held, String verb) {
        if (held.isLockedWithoutPassword()) {
            throw new ApiException(HttpStatus.CONFLICT, held.getHeldId() + " cannot be " + verb
                    + ": the statement is password-protected and its password was not kept, so the file "
                    + "cannot be opened. Review it from the staged rows and the findings, then approve or reject.");
        }
    }

    private static void refuseIfResolved(HeldStatement held, String verb) {
        if (held.getStatus().isResolved()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    held.getHeldId() + " was already " + held.getStatus() + "; it cannot be "
                            + verb + " again.");
        }
    }

    private void notifyStatementReady(ImportJob job) {
        // "bank" is the template's documented fallback, giving "Your bank statement is ready" --
        // this call site has no parser-detected name available, the same reason it never has.
        statementStatusNotifier.notifyReady(job, "bank");
    }
}
