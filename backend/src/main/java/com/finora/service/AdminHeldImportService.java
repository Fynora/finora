package com.finora.service;

import com.finora.dto.HeldImportDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.ImportJob;
import com.finora.exception.ApiException;
import com.finora.imports.blanked.BlankedCopyOutcome;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.imports.original.OriginalContent;
import com.finora.imports.original.OriginalViewHistory;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.storage.StatementStorageException;
import com.finora.repository.ImportJobRepository;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admin triage queue for imports held after an unclassified failure.
 *
 * <p>Mirrors {@link AdminLearningQueueService} deliberately rather than incidentally: the two
 * queues have the same shape (a worker owns the automatic lifecycle, a human owns retry and
 * give-up), and an operator who has learned one should not have to learn the other. The same
 * split applies here -- {@code ImportJobWorker} never resets an attempt budget, and this does,
 * because a human has asserted that the thing that was failing is now fixed.
 *
 * <p><b>Why this is its own permission.</b> Everything reachable from here concerns a real
 * customer's bank statement. {@code detail} hands back the raw parser error, which routinely quotes
 * the input that defeated it, and {@code reprocess} re-runs their document. That is a different
 * kind of access from "see how the import pipeline is doing", so it does not ride on
 * {@code PLATFORM_DIAGNOSTICS_VIEW}, and it is not folded into {@code LEARNING_QUEUE_MANAGE}
 * either -- clearing a merchant-learning backlog should not come with the ability to read
 * statements.
 */
@Service
public class AdminHeldImportService {

    private static final Logger log = LoggerFactory.getLogger(AdminHeldImportService.class);

    /**
     * How many held jobs one Reprocess All may requeue.
     *
     * <p>Smaller than the learning queue's 500 on purpose. A requeued learning event is a cheap
     * database update; a requeued import is a full statement parse on a pool with one core thread,
     * so 500 of them is not a bulk action, it is a self-inflicted outage with a progress bar.
     */
    static final int MAX_REPROCESS_ALL = 50;

    private final ImportJobRepository repository;
    private final ImportJobWorker worker;
    private final AuditService auditService;
    private final StatementContentService statementContentService;
    private final StatementStatusNotifier statusNotifier;
    private final com.finora.imports.passwords.StatementPasswordService statementPasswordService;
    private final OriginalViewHistory originalViewHistory;

    /**
     * The longest message an admin may send a user. Bounded well inside the notification columns
     * (title 300, message 2000) so the outbox insert cannot be the thing that fails the resolve, and
     * short enough that a push notification is not a wall of text.
     */
    static final int MAX_MESSAGE_LENGTH = 500;

    public AdminHeldImportService(ImportJobRepository repository,
                                  ImportJobWorker worker,
                                  AuditService auditService,
                                  StatementContentService statementContentService,
                                  StatementStatusNotifier statusNotifier,
                                  com.finora.imports.passwords.StatementPasswordService statementPasswordService,
                                  OriginalViewHistory originalViewHistory) {
        this.originalViewHistory = originalViewHistory;
        this.statusNotifier = statusNotifier;
        this.statementPasswordService = statementPasswordService;
        this.repository = repository;
        this.worker = worker;
        this.auditService = auditService;
        this.statementContentService = statementContentService;
    }

    /** One page of held jobs, oldest first -- the longest-waiting user is the one to look at. */
    @Transactional(readOnly = true)
    public PagedResponse<HeldImportDto> list(int page, int size) {
        var result = repository.findByStatus(
                ImportJob.Status.HELD_FOR_REVIEW,
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                        Sort.by(Sort.Direction.ASC, "createdAt", "id")));
        return PagedResponse.of(result.map(HeldImportDto::from));
    }

    /** The two counts the queue header shows. Two indexed counts, not a reporting subsystem. */
    @Transactional(readOnly = true)
    public HeldImportDto.Summary summary() {
        return new HeldImportDto.Summary(
                repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW),
                repository.countByStatusAndWasHeldForReviewTrue(ImportJob.Status.QUEUED));
    }

    /**
     * One held job's full diagnostics, including the raw parser error.
     *
     * <p><b>Audited on every call, and that is the point of the method existing separately from
     * {@link #list}.</b> This is where an admin reads text drawn from a real person's bank
     * statement. Browsing the queue stays unaudited because the row form carries no customer
     * content; opening one does not, so it is recorded against the admin who opened it.
     *
     * <p>Not {@code readOnly}: it writes the audit entry.
     */
    @Transactional
    public HeldImportDto.Detail detail(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        auditService.record(actingAdminId, "HELD_IMPORT_VIEWED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        "failureCode", String.valueOf(job.getFailureCode())));
        return HeldImportDto.Detail.from(job, originalViewHistory.forJob(jobId).stream()
                .map(o -> new HeldImportDto.Opening(o.openedAt(), o.openedBy()))
                .toList());
    }

    /**
     * The held job's real bytes for an Admin's in-browser view: the one path left that shows staff a
     * customer's statement. Audited with the typed reason in its own transaction before the bytes are
     * read, as the blanked copy is; a read that then fails leaves the record of the attempt.
     *
     * <p>Restricted to {@code HELD_FOR_REVIEW} the same way {@link #reprocess} and {@link #resolve}
     * are: {@link #require} alone accepts any job, and without the check {@code IMPORT_TRIAGE_MANAGE}
     * would reach any customer's statement by job id, not only the ones sitting in this queue. A
     * saved password that does not open the file is refused rather than handed over locked. The role
     * pin and the reason's length rule live one layer up, on the controller and the view service.
     */
    @Transactional
    public OriginalContent contentForOriginalView(UUID viewerId, UUID jobId, String reason) {
        ImportJob job = require(jobId);
        requireHeld(job, "opened");
        boolean savedPassword = hasSavedPassword(job);
        auditService.recordEvenOnRollback(viewerId, "HELD_IMPORT_ORIGINAL_VIEWED", "ImportJob", jobId,
                Map.of("actorId", viewerId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        "reason", reason,
                        "savedPasswordOnFile", savedPassword));
        return originalContentOf(job, readUnlocked(job, savedPassword, "a view", OriginalContent.CANNOT_BE_SHOWN));
    }

    /** The same bytes for a page of a view already opened (and audited). Not audited again; refuses a
     *  job no longer held, so a release ends every open view. */
    @Transactional(readOnly = true)
    public OriginalContent contentForOpenView(UUID jobId) {
        ImportJob job = require(jobId);
        requireHeld(job, "opened");
        return originalContentOf(job, readUnlocked(job, hasSavedPassword(job), "a view", OriginalContent.CANNOT_BE_SHOWN));
    }

    private static OriginalContent originalContentOf(ImportJob job, byte[] content) {
        return new OriginalContent(job.getId(), job.getUserId(), "IMPORT", job.getId().toString(),
                job.getSourceFormat().toUpperCase(Locale.ROOT), content);
    }

    private boolean hasSavedPassword(ImportJob job) {
        return "PDF".equalsIgnoreCase(job.getSourceFormat())
                && statementPasswordService.hasJobPassword(job.getId());
    }

    /**
     * The stored bytes, unlocked in memory when a password is saved; no unlocked copy is stored. A
     * stored file that cannot be read is a 409 naming the job, as {@code HeldStatementService
     * .readStatement} answers on the trust queue, rather than the catch-all 500: nothing about the
     * request was wrong, the file is gone. A saved password that does not open the file is refused,
     * the refusal ending with {@code consequence}.
     */
    private byte[] readUnlocked(ImportJob job, boolean savedPassword, String purpose, String consequence) {
        byte[] content;
        try {
            content = statementContentService.read(job);
        } catch (StatementStorageException e) {
            log.warn("Stored statement behind held import {} could not be read for {}", job.getId(), purpose, e);
            throw new ApiException(HttpStatus.CONFLICT,
                    "The stored statement file behind held import " + job.getId() + " could not be read.");
        }
        return savedPassword
                ? statementPasswordService.unlockedOrRefused(job, content, "held import " + job.getId(), consequence)
                : content;
    }

    /**
     * The held job's bytes for a blanked copy, unlocked in memory when a password is saved. Same
     * gates as {@link #contentForOriginalView}, under its own action, and without the role pin: the
     * copy that leaves the server is blanked, which is what a support role may receive. The bytes go to
     * {@code BlankedCopyService} and nowhere else; the reference is the job id, never its file name.
     *
     * <p>Audited in its own transaction, before the bytes are read: a failed read rolls this
     * transaction back, and the attempt is the event being recorded. A stored file that cannot be
     * read is a 409 naming the job, the same answer the trust queue gives.
     */
    @Transactional
    public StaffStatementContent contentForBlankedCopy(UUID actingAdminId, UUID jobId) {
        return blankedCopyContent(actingAdminId, jobId);
    }

    /**
     * As {@link #contentForBlankedCopy}, for the automatic Claude fix job: no person is involved, so
     * the audit entry has no actor and says {@code actor: claude-fix}. Every other gate is the
     * staff method's: held only, a saved password unlocks the file or the copy is refused, and an
     * unreadable file is a 409.
     */
    @Transactional
    public StaffStatementContent contentForSystemBlankedCopy(UUID jobId) {
        return blankedCopyContent(null, jobId);
    }

    private StaffStatementContent blankedCopyContent(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        requireHeld(job, "copied");
        boolean savedPassword = hasSavedPassword(job);
        Map<String, Object> details = actorDetails(actingAdminId);
        details.put("subjectUserId", job.getUserId().toString());
        details.put("savedPasswordOnFile", savedPassword);
        auditService.recordEvenOnRollback(actingAdminId, "HELD_IMPORT_BLANKED_COPY", "ImportJob", jobId, details);
        byte[] content = readUnlocked(job, savedPassword, "a blanked copy", "no blanked copy can be made");
        return new StaffStatementContent(job.getId().toString(), job.getSourceFormat(), content);
    }

    /** Who acted, for an audit entry: the admin's id, or {@code actor: claude-fix} when no person did. */
    private static Map<String, Object> actorDetails(UUID actingAdminId) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (actingAdminId == null) {
            details.put("actor", "claude-fix");
        } else {
            details.put("actorId", actingAdminId.toString());
        }
        return details;
    }

    /**
     * The second audit entry for a blanked copy, written once the copy is made or the privacy scan
     * refused it: the ids and the check's result, never statement text. The first entry, written
     * before the read by {@link #contentForBlankedCopy}, records the access; this one what came of
     * it. Nothing here rolls back on a refusal: the caller is not in a transaction, and this
     * method's own commits before the refusal is thrown. A {@code null} actor is the Claude fix job.
     */
    @Transactional
    public void recordBlankedCopyResult(UUID actingAdminId, UUID jobId, BlankedCopyOutcome outcome) {
        ImportJob job = require(jobId);
        Map<String, Object> details = actorDetails(actingAdminId);
        details.put("subjectUserId", job.getUserId().toString());
        details.putAll(outcome.auditDetails());
        auditService.record(actingAdminId, "HELD_IMPORT_BLANKED_COPY_RESULT", "ImportJob", jobId, details);
    }

    /**
     * Sends one held job back to the queue with a fresh attempt budget.
     *
     * <p>Safe to use speculatively: if the parser bug is not actually fixed, the job fails the same
     * way and lands back in this queue. Nothing is lost by trying. A {@code null} actor is the
     * Claude fix job re-reading the statement once its fix is deployed.
     */
    @Transactional
    public HeldImportDto reprocess(UUID actingAdminId, UUID jobId) {
        ImportJob job = require(jobId);
        requireHeld(job, "reprocessed");
        requireNoLiveDuplicate(job);

        String originalFailureCode = job.getFailureCode();
        job.returnToQueueForReprocess(Instant.now());
        repository.save(job);

        // The failure code is cleared off the entity by the transition (a stale code would describe
        // the wrong attempt on the customer's timeline), so the audit entry is the only place the
        // original reason survives. Recording it here is what makes "did fixing X actually clear
        // these?" answerable later.
        Map<String, Object> details = actorDetails(actingAdminId);
        details.put("subjectUserId", job.getUserId().toString());
        details.put("originalFailureCode", String.valueOf(originalFailureCode));
        auditService.record(actingAdminId, "HELD_IMPORT_REPROCESSED", "ImportJob", jobId, details);

        nudgeAfterCommit();
        return HeldImportDto.from(job);
    }

    /**
     * Sends every held job back to the queue, up to {@link #MAX_REPROCESS_ALL}.
     *
     * @return how many were requeued, so the UI can say "12 reprocessed" rather than "done"
     */
    @Transactional
    public int reprocessAll(UUID actingAdminId) {
        // Counted BEFORE anything is mutated. Taking it afterwards would be wrong in a way that is
        // invisible to a mocked test: Hibernate auto-flushes the pending UPDATEs before running a
        // query against the same table, so the count would already exclude everything this call
        // just requeued, and subtracting the batch size again would double-count it away.
        long heldBefore = repository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW);

        List<ImportJob> held = repository.findByStatus(
                ImportJob.Status.HELD_FOR_REVIEW,
                PageRequest.of(0, MAX_REPROCESS_ALL, Sort.by(Sort.Direction.ASC, "createdAt")))
                .getContent();
        if (held.isEmpty()) return 0;

        Instant now = Instant.now();
        // Two filters, and the second one is not redundant.
        //
        // hasNoLiveDuplicate answers "is something ELSE already live on these bytes" -- the case
        // where the user re-uploaded and that import is still running. It cannot see a collision
        // between two members of THIS batch, because neither is live yet when it is asked.
        //
        // That collision is reachable. V134 deliberately excludes HELD_FOR_REVIEW from
        // idx_import_jobs_live_content so a user who was told "no action needed" can re-upload
        // anyway -- which means two held jobs may legitimately share one (user_id, content_hash).
        // Requeuing both makes them both live, and the unique index rejects the second, failing
        // the entire batch with a 500 on what the operator experienced as one click.
        //
        // The oldest wins, since the page is ordered createdAt ASC and that is the submission the
        // user has been waiting on longest. The rest stay held and are reported as skipped; once
        // the winner finishes, a second Reprocess All picks up the next one.
        Set<String> claimed = new HashSet<>();
        List<ImportJob> eligible = held.stream()
                .filter(this::hasNoLiveDuplicate)
                .filter(job -> job.getContentHash() == null
                        || claimed.add(job.getUserId() + ":" + job.getContentHash()))
                .toList();
        eligible.forEach(job -> job.returnToQueueForReprocess(now));
        repository.saveAll(eligible);

        // One entry for the bulk action, not one per job: the thing the admin did was "reprocess
        // everything". The counts are what make it reconstructable -- including what was left
        // behind, because a silently truncated batch reads as a complete one. stillHeld covers both
        // reasons something was left: skipped as a duplicate, or beyond MAX_REPROCESS_ALL.
        auditService.record(actingAdminId, "HELD_IMPORTS_REPROCESSED_BULK", "ImportJob", null,
                Map.of("actorId", actingAdminId.toString(),
                        "count", eligible.size(),
                        "skippedDuplicates", held.size() - eligible.size(),
                        "stillHeld", Math.max(heldBefore - eligible.size(), 0)));

        nudgeAfterCommit();
        return eligible.size();
    }

    /**
     * Closes a held job without fixing it, and tells the user why in the admin's own words.
     *
     * <p>The escape hatch the queue needs to stay useful, for the reason the learning queue's own
     * resolve gives: some statements will never parse -- a bank that publishes an image with no
     * text layer, a file damaged in download -- and with no way to close them the page fills with
     * permanent noise until operators stop reading it.
     *
     * <p>The user was told, when it was held, that we were running additional checks and that no
     * action was needed from them. Closing it silently would leave that promise dangling and put a
     * bare failure behind it, so this now requires a message and delivers it by push and email,
     * inside this transaction (the notification outbox), so "resolved" and "the user is told" commit
     * together and neither can happen without the other. The same words are kept on the job so the
     * failed-import card shows them to a user who opens the app after the email.
     *
     * <p>The message is refused, not truncated, when it is too long, and control characters are
     * stripped: it lands in a customer's inbox and lock screen, and an operator should see that
     * their text did not fit rather than find it silently cut.
     *
     * @throws ApiException 400 for a blank or over-long message; 409 if the job is not held
     */
    @Transactional
    public HeldImportDto resolve(UUID actingAdminId, UUID jobId, String message) {
        ImportJob job = require(jobId);
        requireHeld(job, "resolved");
        String userMessage = cleanMessage(message);

        job.resolveWithoutFix(Instant.now(), userMessage);
        // Flushed NOW, before the notification below, not left for commit. Two admins resolving the
        // same job (or one double-click) make the loser's UPDATE fail its @Version check. Left
        // pending, that failure is raised later, inside NotificationService.request's native insert
        // -- whose catch-all swallows it to protect callers -- which marks this transaction
        // rollback-only and turns the admin's "someone else just resolved this" into a 500
        // UnexpectedRollbackException. Flushing here lets it surface as itself, and
        // GlobalExceptionHandler answers 409.
        repository.saveAndFlush(job);
        auditService.record(actingAdminId, "HELD_IMPORT_RESOLVED", "ImportJob", jobId,
                Map.of("actorId", actingAdminId.toString(),
                        "subjectUserId", job.getUserId().toString(),
                        "message", userMessage));
        statusNotifier.notifyResolved(job, userMessage);
        return HeldImportDto.from(job);
    }

    /**
     * Trims, removes control characters other than line breaks, and enforces the length cap.
     * Line breaks are kept because an admin writing two sentences on two lines means it, and the
     * email path renders them as breaks.
     */
    private static String cleanMessage(String raw) {
        String cleaned = raw == null ? "" : raw
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                // Bidirectional overrides and isolates make text render differently from how it is
                // stored -- a spoofing tool, not something a reply to a customer needs. Other
                // format characters (a zero-width joiner inside an emoji sequence) are left alone.
                .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replace("\r", "")
                .strip();
        if (cleaned.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Write the message the user will receive -- resolving without one leaves them "
                            + "with no explanation.");
        }
        if (cleaned.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The message is " + cleaned.length() + " characters; the limit is "
                            + MAX_MESSAGE_LENGTH + ".");
        }
        return cleaned;
    }

    // --- internals ----------------------------------------------------------------------------

    private ImportJob require(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such import job."));
    }

    private static void requireHeld(ImportJob job, String verb) {
        if (job.getStatus() != ImportJob.Status.HELD_FOR_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a held import can be " + verb + "; this one is " + job.getStatus() + ".");
        }
    }

    /**
     * Refuses a reprocess whose bytes are already live under a different job.
     *
     * <p>A held job is excluded from {@code idx_import_jobs_live_content} (V134), precisely so the
     * user can re-upload the statement they were told to stop worrying about. That leaves one
     * window: if they did re-upload, moving the held job back to QUEUED makes two live jobs for the
     * same (user, document) and the unique index rejects it. Caught here so the operator gets a 409
     * that explains itself, rather than a constraint violation surfacing as a 500.
     *
     * <p>A re-upload held for trust review counts as live too, though the index would allow it:
     * reprocessing this job would replay that upload's staged session and open a second review of
     * it. See {@link ImportJob.Status#OPEN_TO_RESUBMISSION} and {@link ImportJob#ownsItsDocument}.
     */
    private void requireNoLiveDuplicate(ImportJob job) {
        if (!hasNoLiveDuplicate(job)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This user has already re-uploaded the same statement and that import is still "
                            + "live or held for trust review, so this one cannot be reprocessed. "
                            + "Resolve it instead.");
        }
    }

    private boolean hasNoLiveDuplicate(ImportJob job) {
        if (job.getContentHash() == null) return true;
        return repository.findByUserIdAndContentHashAndStatusNotInOrderByCreatedAtDesc(
                        job.getUserId(), job.getContentHash(), ImportJob.Status.OPEN_TO_RESUBMISSION).stream()
                .noneMatch(ImportJob::ownsItsDocument);
    }

    /** Wakes the worker once the requeue has committed -- the same afterCommit discipline
     *  {@code AdminLearningQueueService} and {@code ImportJobService.accept} both use. Nudging
     *  inside the transaction would hand the worker a job id it cannot yet see as QUEUED. */
    private void nudgeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.nudge();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.nudge();
            }
        });
    }
}
