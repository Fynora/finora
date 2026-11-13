package com.finora.imports.blanked;

import com.finora.entity.ImportJob;
import com.finora.entity.RegisteredLayout;
import com.finora.exception.ApiException;
import com.finora.service.StatementStatusNotifier;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.original.OriginalStatementViewService;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.storage.StatementStorageException;
import com.finora.entity.ImportSession;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.UserRepository;
import com.finora.entity.User;
import com.finora.service.AuditService;
import com.finora.imports.passwords.StatementPasswordService;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A blanked copy of one upload of a layout flagged for review, for fixing the reader when nothing
 * was held. Held statements already have a copy of their own; an upload that read with warnings, or
 * failed before the held queue existed, has nothing staff can work from.
 *
 * <p>The rules are those of opening a held statement, because the customer is told the same thing:
 * <ul>
 *   <li>only an upload whose layout is waiting for review right now, and only one the flag names:
 *       its latest flagged upload or the upload that raised one of its reasons. A popular layout
 *       flagged by one bad reading does not open every customer's upload of it, so this is never a
 *       general way into customers' statements;</li>
 *   <li>only a statement the customer still has: staged and not expired, held, or imported and not
 *       deleted. One they cancelled, discarded or deleted, or an account they are closing, is not
 *       ours to open;</li>
 *   <li>a typed reason of 15 to 500 characters, checked before anything is read or audited;</li>
 *   <li>an audit entry, in its own transaction, before the file is read;</li>
 *   <li>once a copy exists: the customer's "opened to fix a reading problem" notice, written in the
 *       same transaction as the result entry, and an email to the Super Admins after commit.</li>
 * </ul>
 * A copy the privacy scan refuses reaches nobody, so it notifies nobody; both entries stand.
 *
 * <p>Only uploads that went through the import queue have a file to copy. The queue stores the
 * upload before it is read and keeps it for the retention window after the job finishes. A direct
 * upload's file waits in its staged session and is deleted with it unless the customer confirms it.
 */
@Service
public class FlaggedLayoutUploadService {

    private static final Logger log = LoggerFactory.getLogger(FlaggedLayoutUploadService.class);

    static final String ACCESS_ACTION = "LAYOUT_UPLOAD_BLANKED_COPY";
    static final String RESULT_ACTION = "LAYOUT_UPLOAD_BLANKED_COPY_RESULT";

    /** One flagged upload, resolved and audited, ready for the copy. */
    public record FlaggedUpload(String reference, String fingerprint, UUID jobId, UUID subjectUserId,
                                String reason, StaffStatementContent content) {}

    private final StatementAnalysisSessionRepository analyses;
    private final RegisteredLayoutRepository layouts;
    private final ImportJobRepository jobs;
    private final StatementContentService statementContent;
    private final StatementPasswordService passwords;
    private final AuditService audit;
    private final StatementStatusNotifier notifier;
    private final ApplicationEventPublisher events;
    private final LayoutUploadBlankedCopyRepository copies;
    private final UserRepository users;
    private final ImportSessionRepository sessions;
    private final StatementImportRepository statementImports;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FlaggedLayoutUploadService(StatementAnalysisSessionRepository analyses, RegisteredLayoutRepository layouts,
                                      ImportJobRepository jobs, StatementContentService statementContent,
                                      StatementPasswordService passwords, AuditService audit,
                                      StatementStatusNotifier notifier, ApplicationEventPublisher events,
                                      LayoutUploadBlankedCopyRepository copies, UserRepository users,
                                      ImportSessionRepository sessions, StatementImportRepository statementImports) {
        this(analyses, layouts, jobs, statementContent, passwords, audit, notifier, events, copies, users,
                sessions, statementImports, Clock.systemUTC());
    }

    FlaggedLayoutUploadService(StatementAnalysisSessionRepository analyses, RegisteredLayoutRepository layouts,
                               ImportJobRepository jobs, StatementContentService statementContent,
                               StatementPasswordService passwords, AuditService audit,
                               StatementStatusNotifier notifier, ApplicationEventPublisher events,
                               LayoutUploadBlankedCopyRepository copies, UserRepository users,
                               ImportSessionRepository sessions, StatementImportRepository statementImports,
                               Clock clock) {
        this.sessions = sessions;
        this.statementImports = statementImports;
        this.analyses = analyses;
        this.layouts = layouts;
        this.jobs = jobs;
        this.statementContent = statementContent;
        this.passwords = passwords;
        this.audit = audit;
        this.notifier = notifier;
        this.events = events;
        this.copies = copies;
        this.users = users;
        this.clock = clock;
    }

    /**
     * The upload's bytes for a blanked copy, unlocked in memory when a password is saved. Refusals
     * before the audit entry (a bad reason, an unknown reference, a layout not waiting for review,
     * an upload with no kept file) read nothing and record nothing. After the entry, a file that
     * cannot be read is a 409 and the entry stands: the attempt is what it records.
     */
    @Transactional
    public FlaggedUpload contentForBlankedCopy(UUID actingAdminId, String reference, String rawReason) {
        String reason = OriginalStatementViewService.validReason(rawReason);
        Copyable copyable = requireCopyable(reference);
        ImportJob job = copyable.job();
        String fingerprint = copyable.fingerprint();

        boolean savedPassword = passwords.hasJobPassword(job.getId());
        Map<String, Object> details = details(actingAdminId, job, reference, fingerprint);
        details.put("reason", reason);
        details.put("savedPasswordOnFile", savedPassword);
        audit.recordEvenOnRollback(actingAdminId, ACCESS_ACTION, "ImportJob", job.getId(), details);

        byte[] content;
        try {
            content = statementContent.read(job);
        } catch (StatementStorageException e) {
            log.warn("Stored statement behind upload {} could not be read for a blanked copy",
                    LogSanitizer.sanitize(reference), e);
            throw new ApiException(HttpStatus.CONFLICT,
                    "The stored file behind upload " + reference + " could not be read.");
        }
        if (savedPassword) {
            content = passwords.unlockedOrRefused(job, content, "upload " + reference, "no blanked copy can be made");
        }
        // The copy's name carries the reference, never the customer's file name.
        return new FlaggedUpload(reference, fingerprint, job.getId(), job.getUserId(), reason,
                new StaffStatementContent(reference, job.getSourceFormat(), content));
    }

    /** An upload that passed every check made before a copy reads anything. */
    private record Copyable(ImportJob job, String fingerprint) {}

    /**
     * Why no copy can be made, as a code for listings. {@code CUSTOMER_STOPPED} covers an import the
     * customer cancelled and an account they are closing or have closed, without saying which: the
     * layout pages carry no personal data (AdminReadAuditPolicy), so a listing does not name a
     * customer's choice. The copy itself answers in words, to the Admin who asked for it.
     */
    public enum Refusal { UNKNOWN_UPLOAD, NOT_FLAGGED, NOT_FLAGGING_UPLOAD, NO_KEPT_FILE, CUSTOMER_STOPPED, NOT_PDF }

    /** A refusal before anything is read: the 409 or 404 the copy answers, carrying its code. */
    private static final class RefusedCopy extends ApiException {
        private final Refusal refusal;

        RefusedCopy(Refusal refusal, HttpStatus status, String message) {
            super(status, message);
            this.refusal = refusal;
        }
    }

    /**
     * Why no copy of this upload can be made, or empty when one can -- the refusals the copy itself
     * makes before it reads the file or records anything, in the same order and words, so the
     * review queue shows the button only where pressing it can work. Reads nothing and records
     * nothing. A stored file swept since is not seen here: the copy finds it gone and says so.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Refusal> copyRefusal(String reference) {
        try {
            requireCopyable(reference);
            return java.util.Optional.empty();
        } catch (RefusedCopy refused) {
            return java.util.Optional.of(refused.refusal);
        }
    }

    private Copyable requireCopyable(String reference) {
        StatementAnalysisSession analysis = analyses.findByReference(reference).orElseThrow(() ->
                new RefusedCopy(Refusal.UNKNOWN_UPLOAD, HttpStatus.NOT_FOUND, "No upload has the reference " + reference + "."));
        String fingerprint = analysis.getLayoutFingerprint();
        RegisteredLayout layout = fingerprint == null ? null
                : layouts.findByFingerprint(fingerprint).filter(RegisteredLayout::isNeedsReview).orElse(null);
        if (layout == null) {
            throw new RefusedCopy(Refusal.NOT_FLAGGED, HttpStatus.CONFLICT, "Upload " + reference
                    + " is not of a layout waiting for review, so no copy of it can be made here.");
        }
        if (!namedByFlag(layout, analysis.getReference())) {
            throw new RefusedCopy(Refusal.NOT_FLAGGING_UPLOAD, HttpStatus.CONFLICT, "Upload " + reference
                    + " did not raise this layout's review, so no copy of it can be made here. Copy the upload"
                    + " the review queue names.");
        }
        ImportJob job = jobOf(analysis);
        requireCustomerStillHere(job, reference);
        // The copy is a PDF trace; BlankedCopyService refuses anything else, but only after the
        // file has been read and the access recorded. Refused here, nothing is read or recorded.
        if (!"PDF".equalsIgnoreCase(job.getSourceFormat())) {
            throw new RefusedCopy(Refusal.NOT_PDF, HttpStatus.CONFLICT, "Blanked copy not available for CSV yet.");
        }
        return new Copyable(job, fingerprint);
    }

    /**
     * The second entry, once the copy is made or refused: the ids and the check's result, never
     * statement text. A made copy also tells the customer, in this transaction, and the Super
     * Admins, after it commits; and it is recorded for the customer's import timeline (V313),
     * which is where they can look up what the notice told them.
     */
    @Transactional
    public void recordBlankedCopyResult(UUID actingAdminId, FlaggedUpload upload, BlankedCopyOutcome outcome) {
        ImportJob job = jobs.findById(upload.jobId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Upload " + upload.reference() + " no longer exists."));
        Map<String, Object> details = details(actingAdminId, job, upload.reference(), upload.fingerprint());
        details.putAll(outcome.auditDetails());
        audit.record(actingAdminId, RESULT_ACTION, "ImportJob", job.getId(), details);
        if (BlankedCopyOutcome.REFUSED.equals(outcome.check())) return;

        Instant now = clock.instant();
        copies.save(LayoutUploadBlankedCopy.made(job.getId(), job.getUserId(), actingAdminId, upload.reference(), now));
        notifier.notifyOpenedForFix(job.getUserId(), job.getId(), OriginalStatementViewService.noticeDay(now));
        events.publishEvent(new LayoutUploadCopied(actingAdminId, upload.reference(), upload.fingerprint(),
                job.getId(), upload.reason(), now));
    }

    /**
     * The queue job behind the upload. The analysis row names the staged session; the job that
     * produced that session is the one holding the file. An upload with no job was a direct one,
     * whose file is never kept unless it was confirmed.
     */
    private ImportJob jobOf(StatementAnalysisSession analysis) {
        UUID sessionId = analysis.getImportSessionId();
        // Guarded: a derived query matches IS NULL, so findByImportSessionId(null) would return
        // every job that never recorded a session.
        return (sessionId == null ? java.util.List.<ImportJob>of() : jobs.findByImportSessionId(sessionId))
                .stream().findFirst()
                .orElseThrow(() -> new RefusedCopy(Refusal.NO_KEPT_FILE, HttpStatus.CONFLICT, "Upload " + analysis.getReference()
                        + " has no kept file: it was not made through the import queue, or its import no longer exists."));
    }

    /**
     * Whether the flag names this upload: its latest flagged upload, or the one that raised a reason
     * it still carries (V270). Any other upload of the layout read without raising this review.
     */
    private static boolean namedByFlag(RegisteredLayout layout, String reference) {
        return reference.equals(layout.getReviewAnalysisReference())
                || layout.getReviewReasonSources().containsValue(reference);
    }

    /**
     * The customer is not waiting on us unless the statement is still theirs: an import they
     * cancelled, a staging they discarded or let expire, an import they deleted, or an account they
     * are leaving or have left, is not ours to open. Refused before anything is read or recorded.
     */
    private void requireCustomerStillHere(ImportJob job, String reference) {
        if (job.getStatus() == ImportJob.Status.CANCELLED) {
            throw new RefusedCopy(Refusal.CUSTOMER_STOPPED, HttpStatus.CONFLICT,
                    "The customer cancelled upload " + reference + ", so no copy of it can be made.");
        }
        if (!customerStillHasIt(job)) {
            throw new RefusedCopy(Refusal.CUSTOMER_STOPPED, HttpStatus.CONFLICT, "The customer discarded or deleted"
                    + " upload " + reference + ", or it expired before they imported it, so no copy of it can be made.");
        }
        String status = users.findById(job.getUserId()).map(User::getStatus).orElse(User.STATUS_DELETED);
        if (User.STATUS_PENDING_DELETION.equals(status) || User.STATUS_DELETED.equals(status)
                || User.STATUS_DEACTIVATED.equals(status)) {
            throw new RefusedCopy(Refusal.CUSTOMER_STOPPED, HttpStatus.CONFLICT, "The account behind upload " + reference
                    + " is closed or being closed, so no copy of it can be made.");
        }
    }

    /**
     * The statement is still the customer's: held by us (they are waiting on the review), staged
     * and still confirmable, or imported and not deleted. The queue keeps the job's file for the
     * retention window whatever the customer did with the staging, so the job alone cannot say.
     * Nothing links an import back to its job (statement_imports.import_job_id is never filled), so
     * an import is found by its owner and the hash of the uploaded bytes, which the job and every
     * account section of that upload share; deleted imports are hidden by StatementImport's own
     * restriction. Another upload of the very same file that the customer kept counts: they still
     * have that statement.
     */
    private boolean customerStillHasIt(ImportJob job) {
        if (job.getStatus() == ImportJob.Status.HELD_FOR_REVIEW
                || job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW) {
            return true;
        }
        Instant now = clock.instant();
        boolean staged = job.getImportSessionId() != null && sessions.findById(job.getImportSessionId())
                .filter(s -> ImportSession.STATUS_STAGED.equals(s.getStatus()))
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(now))
                .isPresent();
        return staged || (job.getContentHash() != null && statementImports
                .findFirstByUserIdAndContentHashOrderByImportedAtDesc(job.getUserId(), job.getContentHash()).isPresent());
    }

    private static Map<String, Object> details(UUID actingAdminId, ImportJob job, String reference, String fingerprint) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actorId", actingAdminId.toString());
        details.put("subjectUserId", job.getUserId().toString());
        details.put("analysisReference", reference);
        details.put("layoutFingerprint", fingerprint);
        return details;
    }
}
