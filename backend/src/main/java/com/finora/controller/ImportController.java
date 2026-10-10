package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportDto.*;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.security.CurrentUser;
import com.finora.imports.ImportService;
import com.finora.imports.StatementUpload;
import com.finora.imports.jobs.StagingTrustGate;
import com.finora.uploads.UploadScanGate;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    // How many recent failures GET /failures returns. A fixed recent-window cap rather than real
    // pagination -- Premium Import Reliability v1 §2.1 scopes this as "enough to render a list",
    // the same bar ImportSessionSummaryDto's sibling endpoint already sets; a paginated failure
    // history is a later, separate concern if it turns out to be needed.
    private static final int RECENT_FAILURES_LIMIT = 20;

    private final ImportService importService;
    private final ImportSessionService importSessionService;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;
    private final StatementAnalysisRecorder analysisRecorder;
    private final UploadScanGate uploadScanGate;
    private final StagingTrustGate stagingTrustGate;

    public ImportController(ImportService importService, ImportSessionService importSessionService,
                             ImportConcurrencyLimiter concurrencyLimiter, CurrentUser currentUser,
                             StatementAnalysisRecorder analysisRecorder, UploadScanGate uploadScanGate,
                             StagingTrustGate stagingTrustGate) {
        this.uploadScanGate = uploadScanGate;
        this.stagingTrustGate = stagingTrustGate;
        this.importService = importService;
        this.importSessionService = importSessionService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
        this.analysisRecorder = analysisRecorder;
    }

    // ADR-0002: staging now persists the reviewed-later state server-side, so a dropped session
    // doesn't lose the whole upload+parse. Returns the session id alongside the same staging
    // payload as before.
    //
    // Gated through ImportConcurrencyLimiter -- see that class's own doc comment for the full
    // reasoning. This is the actual CPU/DB-heavy work (parsing, categorization, duplicate
    // detection), so it's the one that needs bounding under a burst, not every endpoint in this
    // controller.
    @PostMapping(value = "/csv/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<StagingSessionResponse>> stage(@RequestParam("file") MultipartFile file) throws Exception {
        UUID userId = currentUser.id();
        try {
            // Before the limiter, deliberately: rejecting an empty file or a PDF posted to the CSV
            // endpoint should not consume one of the six permits the expensive work is gated behind
            // (BH-043: an instant accept/reject now, not a queue -- see ImportConcurrencyLimiter).
            StatementUpload.requireReadable(file, StatementUpload.Format.CSV);
            // After the cheap structural check, before the limiter (audit F-18): a rejected file
            // costs no scanner round trip if it was never a CSV, and no import permit if it was.
            uploadScanGate.requireClean(file, userId, "statement-import");
        } catch (RuntimeException refused) {
            // Gate 1 spec §5.1: a refusal before reading used to leave no record at all.
            analysisRecorder.recordRejected(userId, "CSV", refused);
            throw refused;
        }
        // The trust check runs here, after staging, exactly as the worker runs it after staging a
        // queued upload -- see StagingTrustGate. Inside the permit: it is part of staging this file.
        StagingSessionResponse staged = gated("CSV", userId, () -> stagingTrustGate.check(
                userId, StatementUpload.safeFileName(file, "statement.csv"), file.getBytes(),
                importService.parseAndStageWithSession(userId, file)));
        // Outside the permit: a plan lookup, not parsing work. See FreePlanLimitNotice.
        return ResponseEntity.ok(ApiResponse.ok(
                staged.withFreePlanLimit(importService.freePlanLimitNotice(userId, staged.staging(), null))));
    }

    // PDF Milestone 1 (com.finora.imports.pdf) -- digital/text-based bank statements only, no
    // OCR/scanned PDFs yet. Everything below this staging call (confirm, sessions list/get/
    // delete) is completely unaware whether a session came from here or from /csv/stage above --
    // both produce the identical StagingSessionResponse/ImportSession shape, so nothing else in
    // this controller needed to change for PDF support.
    //
    // Response shape changed from StagingSessionResponse to PdfStagingSessionResponse to carry a
    // multi-account PDF's several detected sections (e.g. HSBC's composite statement, which
    // bundles a savings-account section and a credit-card section in one file) -- the
    // single-account case (multiAccount: false) still carries the exact same `staging` payload
    // this endpoint always returned, just wrapped in the new envelope.
    //
    // `password` is optional and travels in the multipart BODY, never as a query parameter -- a
    // document password in a URL would be captured by access logs, browser history and referrers.
    // It is used to open the document and then discarded; it is not stored on the ImportSession
    // and not logged. Clients that never send it behave exactly as they did before.
    @PostMapping(value = "/pdf/stage", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<PdfStagingSessionResponse>> stagePdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password) throws Exception {
        UUID userId = currentUser.id();
        try {
            StatementUpload.requireReadable(file, StatementUpload.Format.PDF);
            uploadScanGate.requireClean(file, userId, "statement-import");
        } catch (RuntimeException refused) {
            analysisRecorder.recordRejected(userId, "PDF", refused);
            throw refused;
        }
        // The trust check, as for /csv/stage above. A locked PDF is the usual reason a statement
        // comes here rather than to the queue: the password is not kept, so a hold is reviewed from
        // its staged rows (HeldStatement.lockedWithoutPassword).
        PdfStagingSessionResponse staged = gated("PDF", userId, () -> stagingTrustGate.check(
                userId, StatementUpload.safeFileName(file, "statement.pdf"), file.getBytes(),
                importService.parseAndStagePdfWithSession(userId, file, password)));
        // Every section of a composite statement, as confirm-multi judges them. See FreePlanLimitNotice.
        return ResponseEntity.ok(ApiResponse.ok(staged.withFreePlanLimit(
                importService.freePlanLimitNotice(userId, staged.staging(), staged.multiAccount() ? staged.sections() : null))));
    }

    /**
     * {@link ImportConcurrencyLimiter#runGated}, recording the one refusal that is its own: no
     * processing slot (Gate 1 spec §5.1). Only {@code IMPORT_SYSTEM_BUSY}, which the limiter alone
     * throws and only before the work starts -- anything thrown by the work itself reached the
     * parser, and {@code ImportService} has already recorded that as a FAILED read with the file's
     * name and hash. Recording it here too would count one upload twice.
     */
    private <T> T gated(String sourceFormat, UUID userId, java.util.concurrent.Callable<T> work) throws Exception {
        try {
            return concurrencyLimiter.runGated(work);
        } catch (com.finora.exception.ApiException refused) {
            if (refused.getCode() == com.finora.exception.ErrorCode.IMPORT_SYSTEM_BUSY) {
                analysisRecorder.recordRejected(userId, sourceFormat, refused);
            }
            throw refused;
        }
    }

    // ADR-0002: plain JSON now, not multipart -- the file no longer needs to be re-uploaded here,
    // since it's already persisted on the ImportSession from staging (looked up via
    // request.sessionId()).
    // Bug fix: the Free-tier statement-period cap (plans.ts's "Statements longer than one
    // month" Plus/Premium promise) used to be enforced HERE, against request.statementPeriodStart()/
    // End() -- values the client echoes back from staging (see ConfirmRequest's own doc comment).
    // That is fine for what those fields are normally used for (persisted display data, reviewed
    // by the user on the confirm screen before submitting), but it made the entitlement gate
    // itself trust client input for a security decision: anyone editing the request body (or a
    // client bug) could send a shortened or null period and the whole cap silently never fired,
    // no race or timing needed. The server already independently computes and PERSISTS this same
    // period at staging time (ImportSession.detectedAccountJson/sectionsJson) -- the check now
    // lives in ImportService, against that server-derived data, once the session is loaded, the
    // same trust boundary ADR-0002 already established for the confirmed row list itself
    // (ConfirmedRowIntegrity.requireSameRows) rather than trusting the client's echoed rows.
    @PostMapping("/csv/confirm")
    public ResponseEntity<ApiResponse<ConfirmResponse>> confirm(@Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(importService.confirmSession(currentUser.id(), request), "Import complete"));
    }

    // Confirms every account section of a multi-account PDF staging session together (see
    // ImportService.confirmMultiSection) -- used only when /pdf/stage returned multiAccount: true.
    // Same server-derived-period gate as confirm() above, enforced inside confirmMultiSection.
    @PostMapping("/pdf/confirm-multi")
    public ResponseEntity<ApiResponse<MultiAccountConfirmResponse>> confirmMulti(@Valid @RequestBody MultiAccountConfirmRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(importService.confirmMultiSection(currentUser.id(), request), "Import complete"));
    }

    // ADR-0002: "your unfinished imports" -- lets the frontend offer to resume a staged-but-not-
    // yet-confirmed session instead of it silently existing only until it expires.
    //
    // Deliberately ImportSessionService.listResumableSessions(), not listActiveSessions() --
    // toSummary() below calls readStagedRows(), which only a SINGLE_ACCOUNT session supports. Using
    // listActiveSessions() here broke this endpoint for its entire response whenever the caller had
    // even one staged MULTI_ACCOUNT PDF session (see /pdf/stage's own doc comment), not just that
    // one session. listResumableSessions() owns the kind filtering so this controller doesn't have
    // to know which kinds toSummary() can actually handle.
    @GetMapping("/sessions")
    public ApiResponse<List<ImportSessionSummaryDto>> listSessions() {
        List<ImportSessionSummaryDto> sessions = importSessionService.listResumableSessions(currentUser.id()).stream()
                .map(this::toSummary)
                .toList();
        return ApiResponse.ok(sessions);
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<StagingSessionResponse> getSession(@PathVariable UUID id) {
        ImportSession session = importSessionService.getOwnedSession(currentUser.id(), id);
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        int dupCount = (int) rows.stream().filter(StagedRow::likelyDuplicate).count();
        // unparseableRows is intentionally NOT persisted on ImportSession (v1 scope -- see
        // docs/engineering/financial-document-intelligence-principles.md's "Never lose
        // information" section) -- a resumed session shows the rows that DID stage correctly
        // exactly as before, but a row that failed to parse is only visible in the original
        // staging response, not after a later resume. Accepted trade-off, not an oversight.
        //
        // verification IS persisted (verification_report_json) and read back here -- see
        // ImportSession.getVerificationReportJson()'s own doc comment. Without this, a resumed
        // session and the async job queue's completion path (which resolves review through this
        // same endpoint) always reported verification=null even when the original staging call
        // had already computed one.
        StagingResponse staging = new StagingResponse(rows, rows.size(), dupCount,
                importSessionService.readDetectedAccount(session), List.of(), importSessionService.readVerification(session));
        // The async job queue resolves review through this endpoint too, so the re-upload notice has
        // to be here as well as on the synchronous staging responses.
        // Same early warning as the staging responses: a queued upload and "Continue Import" both
        // open the review from here. Judged on the plan as it is now, so an upgrade clears it.
        return ApiResponse.ok(new StagingSessionResponse(session.getId(), staging,
                importService.previousImportOf(currentUser.id(), session.getContentHash()))
                .withFreePlanLimit(importService.freePlanLimitNotice(currentUser.id(), staging, null)));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable UUID id) {
        importSessionService.deleteSession(currentUser.id(), id);
        return ApiResponse.ok(null, "Import session discarded");
    }

    // Premium Import Reliability v1, §2.1: "your recent failed imports" -- a document that never
    // got far enough to become an ImportSession at all (a scanned PDF, no header found, zero
    // transactions extracted) previously left no trace the customer who uploaded it could ever see
    // again. ImportService.recordParseFailure already writes this row on every sync-path failure,
    // customer and admin; this endpoint is the first thing that reads it back for the customer who
    // owns it, filtered to their own CUSTOMER_IMPORT failures only -- never another user's rows,
    // and never an ADMIN_ANALYSIS probe even if the same user happens to also be an admin.
    @GetMapping("/failures")
    public ApiResponse<List<ImportFailureSummaryDto>> listFailures() {
        // Unresolved only: a failure whose file the user has since imported is left out -- see
        // StatementAnalysisRecorder.recentUnresolvedCustomerFailures.
        return ApiResponse.ok(analysisRecorder.recentUnresolvedCustomerFailures(currentUser.id(), RECENT_FAILURES_LIMIT));
    }

    private ImportSessionSummaryDto toSummary(ImportSession session) {
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        return new ImportSessionSummaryDto(session.getId(), session.getFileName(), rows.size(),
                session.getCreatedAt(), session.getExpiresAt());
    }
}
