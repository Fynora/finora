package com.finora.imports.jobs;

import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.exception.ApiException;
import com.finora.imports.ImportSessionService;
import com.finora.imports.StatementUpload;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.service.HeldStatementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * The trust check for statements staged through the synchronous endpoints
 * ({@code POST /api/v1/import/csv/stage}, {@code /pdf/stage}).
 *
 * <p>Only the queue used to run it ({@code ImportJobWorker}), so a statement staged here -- in
 * practice a locked PDF whose user did not tick "keep password", which the clients send down this
 * path -- reached the user's confirm step even when its own extraction contradicted itself. This
 * gives it the queue's guarantee: if {@link TrustPredicate} would hold the statement, a job is
 * opened already held, with an ordinary review record, and the confirm gate refuses the staged rows
 * until an operator approves them. The response names that job, and the client follows it to the
 * same "being checked" state a queued hold shows.
 *
 * <h2>Fails closed</h2>
 *
 * <p>If the hold cannot be opened -- no object storage to keep the file in, a storage or database
 * failure -- the staged session is discarded and the upload refused, so the rows cannot be
 * confirmed unreviewed. The worker fails closed differently (it keeps a held job with no record),
 * because its session already has a job pointing at it; here nothing has been shown to the user
 * yet, and a retry is one upload away.
 *
 * <h2>The window before the hold</h2>
 *
 * <p>The session is committed by staging, before this runs, and the file is stored before the hold
 * is written (a network write is never made inside a transaction, BH-018). In that window the
 * session is not yet blocked. Its id has not been returned to this client, so reaching confirm in
 * it would take another tab's "continue previous import" list and a confirm request with the rows
 * before this response is sent; the hold takes the session's row lock, so a claim that lands while
 * it is being written waits for it and is refused.
 */
@Component
public class StagingTrustGate {

    private static final Logger log = LoggerFactory.getLogger(StagingTrustGate.class);

    private final HeldStatementService heldStatementService;
    private final ImportJobService importJobService;
    private final ImportSessionService importSessionService;
    private final ParserVersionProvider parserVersionProvider;
    private final ImportVerificationRecorder verificationRecorder;

    public StagingTrustGate(HeldStatementService heldStatementService, ImportJobService importJobService,
                            ImportSessionService importSessionService, ParserVersionProvider parserVersionProvider,
                            ImportVerificationRecorder verificationRecorder) {
        this.heldStatementService = heldStatementService;
        this.importJobService = importJobService;
        this.importSessionService = importSessionService;
        this.parserVersionProvider = parserVersionProvider;
        this.verificationRecorder = verificationRecorder;
    }

    public StagingSessionResponse check(UUID userId, String fileName, byte[] content,
                                        StagingSessionResponse response) throws IOException {
        return heldJobOf(userId, fileName, content, StatementUpload.Format.CSV, StagedForJob.of(response))
                .map(response::heldForReview)
                .orElse(response);
    }

    public PdfStagingSessionResponse check(UUID userId, String fileName, byte[] content,
                                           PdfStagingSessionResponse response) throws IOException {
        return heldJobOf(userId, fileName, content, StatementUpload.Format.PDF, StagedForJob.of(response))
                .map(response::heldForReview)
                .orElse(response);
    }

    /** The held job the client should follow, or empty when the rows may go to review. */
    private Optional<UUID> heldJobOf(UUID userId, String fileName, byte[] content,
                                     StatementUpload.Format format, StagedForJob staged) throws IOException {
        // A replayed session -- the same bytes staged again, by this endpoint or the queue -- keeps
        // the review it already has: approved stays released, open or rejected stays blocked. The
        // worker applies the same rule to a replay (HeldStatementService.priorReviewOf).
        Optional<HeldStatementService.StagedSessionReview> existing =
                heldStatementService.reviewOfStagedSession(staged.sessionId());
        if (existing.isPresent()) {
            if (existing.get().blocks() && existing.get().jobId() == null) {
                // Not reachable through any transition today. The confirm gate still refuses the
                // session; the client just has no job to follow.
                log.warn("Staged session {} is blocked by a {} review with no job carrying it",
                        staged.sessionId(), existing.get().status());
            }
            return existing.get().blocks() ? Optional.ofNullable(existing.get().jobId()) : Optional.empty();
        }

        // UTC, as the worker anchors it, so the future-period rule cannot depend on where this runs.
        HoldDecision decision = TrustPredicate.evaluate(
                staged.verificationReports(), staged.statementPeriods(), LocalDate.now(ZoneOffset.UTC));
        if (!decision.hold()) return Optional.empty();

        // A locked PDF reached this endpoint with a password that was used once and not kept, so
        // the stored copy stays locked: the reviewer works from the rows (V258).
        boolean lockedWithoutPassword = format == StatementUpload.Format.PDF
                && PdfTextExtractor.needsPassword(new ByteArrayInputStream(content));

        ImportJobService.StoredUpload stored;
        try {
            stored = importJobService.storeForHold(content);
        } catch (IOException | RuntimeException e) {
            throw failClosed(staged.sessionId(), e);
        }
        HeldStatementService.StagedHold hold;
        try {
            hold = heldStatementService.holdStagedUpload(userId, fileName, format.name(), stored.address(),
                    stored.encryptionKeyId(), staged, decision, parserVersionProvider.current(),
                    lockedWithoutPassword);
        } catch (RuntimeException e) {
            importJobService.discardStoredForHold(stored);
            throw failClosed(staged.sessionId(), e);
        }
        if (!hold.created()) {
            // Another upload of these bytes opened the review first; this copy names nothing.
            importJobService.discardStoredForHold(stored);
        } else {
            recordFindings(hold.jobId(), staged);
        }
        return Optional.ofNullable(hold.jobId());
    }

    /**
     * The rows were distrusted and could not be held: remove them so they cannot be confirmed, and
     * refuse the upload. Kept only when a held job already points at the session -- that review
     * blocks it.
     */
    private RuntimeException failClosed(UUID sessionId, Exception cause) {
        try {
            boolean discarded = importSessionService.discardUnlessUnderReview(sessionId);
            report(sessionId, cause, io.sentry.SentryLevel.WARNING);
            if (discarded) {
                log.error("Could not hold staged import session {} for trust review; discarded it so its "
                        + "rows cannot be confirmed unreviewed", sessionId, cause);
            } else {
                log.error("Could not hold staged import session {} for trust review; kept it, because a "
                        + "held job already blocks it", sessionId, cause);
            }
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            // Paged: sentry.logging is off, so the log line alone reaches no one.
            report(sessionId, cause, io.sentry.SentryLevel.ERROR);
            log.error("Could not hold staged import session {} for trust review, and could not discard it: "
                    + "its rows may be confirmable unreviewed", sessionId, cause);
        }
        if (cause instanceof ApiException api) return api;
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "We couldn't finish checking this statement, so nothing was saved. Please upload it again "
                        + "in a moment.");
    }

    private static void report(UUID sessionId, Throwable cause, io.sentry.SentryLevel level) {
        io.sentry.Sentry.withScope(scope -> {
            scope.setTag("phase", "staging-trust-hold");
            scope.setTag("importSessionId", String.valueOf(sessionId));
            scope.setLevel(level);
            io.sentry.Sentry.captureException(cause);
        });
    }

    /**
     * The per-rule findings the review shows, keyed by the job as the worker keys them
     * ({@code HeldStatementService.detail} reads them by job). Swallowing its failures, as the
     * worker does: the hold is already in place, and a reviewer still has the trigger summary.
     */
    private void recordFindings(UUID jobId, StagedForJob staged) {
        if (staged.verificationReports().isEmpty()) return;
        try {
            verificationRecorder.recordForJob(jobId, staged.verificationReports());
        } catch (RuntimeException e) {
            log.warn("Could not record verification findings for held import job {}; the hold itself is "
                    + "unaffected", jobId, e);
        }
    }
}
