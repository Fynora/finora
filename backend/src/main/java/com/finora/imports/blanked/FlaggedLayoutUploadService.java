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
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.UserRepository;
import com.finora.entity.User;
import com.finora.service.AuditService;
import com.finora.imports.passwords.StatementPasswordService;
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
 *   <li>only an upload whose layout is waiting for review right now, so this is never a general way
 *       into customers' statements;</li>
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
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FlaggedLayoutUploadService(StatementAnalysisSessionRepository analyses, RegisteredLayoutRepository layouts,
                                      ImportJobRepository jobs, StatementContentService statementContent,
                                      StatementPasswordService passwords, AuditService audit,
                                      StatementStatusNotifier notifier, ApplicationEventPublisher events,
                                      LayoutUploadBlankedCopyRepository copies, UserRepository users) {
        this(analyses, layouts, jobs, statementContent, passwords, audit, notifier, events, copies, users,
                Clock.systemUTC());
    }

    FlaggedLayoutUploadService(StatementAnalysisSessionRepository analyses, RegisteredLayoutRepository layouts,
                               ImportJobRepository jobs, StatementContentService statementContent,
                               StatementPasswordService passwords, AuditService audit,
                               StatementStatusNotifier notifier, ApplicationEventPublisher events,
                               LayoutUploadBlankedCopyRepository copies, UserRepository users, Clock clock) {
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
        StatementAnalysisSession analysis = analyses.findByReference(reference).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No upload has the reference " + reference + "."));
        String fingerprint = analysis.getLayoutFingerprint();
        boolean flagged = fingerprint != null
                && layouts.findByFingerprint(fingerprint).map(RegisteredLayout::isNeedsReview).orElse(false);
        if (!flagged) {
            throw new ApiException(HttpStatus.CONFLICT, "Upload " + reference
                    + " is not of a layout waiting for review, so no copy of it can be made here.");
        }
        ImportJob job = jobOf(analysis);
        requireCustomerStillHere(job, reference);
        // The copy is a PDF trace; BlankedCopyService refuses anything else, but only after this
        // method has read the file and recorded the access. Refused here, nothing is read or recorded.
        if (!"PDF".equalsIgnoreCase(job.getSourceFormat())) {
            throw new ApiException(HttpStatus.CONFLICT, "Blanked copy not available for CSV yet.");
        }

        boolean savedPassword = passwords.hasJobPassword(job.getId());
        Map<String, Object> details = details(actingAdminId, job, reference, fingerprint);
        details.put("reason", reason);
        details.put("savedPasswordOnFile", savedPassword);
        audit.recordEvenOnRollback(actingAdminId, ACCESS_ACTION, "ImportJob", job.getId(), details);

        byte[] content;
        try {
            content = statementContent.read(job);
        } catch (StatementStorageException e) {
            log.warn("Stored statement behind upload {} could not be read for a blanked copy", reference, e);
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
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Upload " + analysis.getReference()
                        + " has no kept file: it was not made through the import queue, or its import no longer exists."));
    }

    /**
     * Nothing about this upload was held, so the customer is not waiting on us: an import they
     * cancelled, or an account they are leaving or have left, is not ours to open. Refused before
     * anything is read or recorded.
     */
    private void requireCustomerStillHere(ImportJob job, String reference) {
        if (job.getStatus() == ImportJob.Status.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The customer cancelled upload " + reference + ", so no copy of it can be made.");
        }
        String status = users.findById(job.getUserId()).map(User::getStatus).orElse(User.STATUS_DELETED);
        if (User.STATUS_PENDING_DELETION.equals(status) || User.STATUS_DELETED.equals(status)
                || User.STATUS_DEACTIVATED.equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "The account behind upload " + reference
                    + " is closed or being closed, so no copy of it can be made.");
        }
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
