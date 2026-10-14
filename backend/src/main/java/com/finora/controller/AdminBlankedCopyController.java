package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BlankedCopyDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.blanked.BlankedCopy;
import com.finora.imports.blanked.BlankedCopyOutcome;
import com.finora.imports.blanked.BlankedCopyRefusedException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.security.CurrentUser;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Blanked copies of held statements: the one way statement-derived data leaves the server in
 * normal work. Gated by each queue's own permission, deliberately without the ADMIN role pin the
 * real-document endpoints carry -- the copy is blanked, which is what a support role may receive.
 * Behind {@code app.blanked-copy.enabled}: off, the copy endpoints answer 404 before anything is
 * read or audited.
 *
 * <p>No class-level {@code @PreAuthorize}: the two copy endpoints need different queue permissions,
 * and a method-level annotation replaces a class-level one rather than adding to it, so each handler
 * carries its own.
 *
 * <p>Making a copy reads the statement (OCR for a scanned one) and reads it twice more, on the
 * request thread, so it takes an import slot from {@link ImportConcurrencyLimiter} like every other
 * parse. A busy server refuses at once with the import queue's own 503 (with a message of its own),
 * before the audit row is written and before any bytes are read.
 *
 * <p>Two audit entries per copy: the access, written by the queue's service before the read, and
 * the result (see {@link BlankedCopyOutcome}), written once the copy is made or refused.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBlankedCopyController {

    /** What a busy server says on the copy endpoints; see {@link #gated}. */
    static final String BUSY_MESSAGE =
            "The server is busy reading statements right now. Try the blanked copy again in a moment.";

    private final AdminHeldImportService heldImports;
    private final HeldStatementService heldStatements;
    private final BlankedCopyService blankedCopies;
    private final CurrentUser currentUser;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final boolean enabled;

    public AdminBlankedCopyController(AdminHeldImportService heldImports, HeldStatementService heldStatements,
                                      BlankedCopyService blankedCopies, CurrentUser currentUser,
                                      ImportConcurrencyLimiter concurrencyLimiter,
                                      @Value("${app.blanked-copy.enabled:false}") boolean enabled) {
        this.heldImports = heldImports;
        this.heldStatements = heldStatements;
        this.blankedCopies = blankedCopies;
        this.currentUser = currentUser;
        this.concurrencyLimiter = concurrencyLimiter;
        this.enabled = enabled;
    }

    /** Whether the copy endpoints exist, so the portal can show or hide the button. */
    @PreAuthorize("hasAnyAuthority('IMPORT_TRIAGE_MANAGE', 'TRUST_REVIEW_MANAGE')")
    @GetMapping("/blanked-copy/status")
    public ApiResponse<BlankedCopyDto.SwitchStatus> status() {
        return ApiResponse.ok(new BlankedCopyDto.SwitchStatus(enabled));
    }

    @PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE')")
    @GetMapping("/held-imports/{jobId}/blanked-copy")
    public ApiResponse<BlankedCopyDto.Response> heldImport(@PathVariable UUID jobId) throws Exception {
        requireEnabled();
        UUID actor = currentUser.id();
        return ApiResponse.ok(BlankedCopyDto.Response.of(gated(
                () -> makeAndRecord(heldImports.contentForBlankedCopy(actor, jobId),
                        outcome -> heldImports.recordBlankedCopyResult(actor, jobId, outcome)))));
    }

    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")
    @GetMapping("/held-statements/{heldId}/blanked-copy")
    public ApiResponse<BlankedCopyDto.Response> heldStatement(@PathVariable String heldId) throws Exception {
        requireEnabled();
        UUID actor = currentUser.id();
        return ApiResponse.ok(BlankedCopyDto.Response.of(gated(
                () -> makeAndRecord(heldStatements.contentForBlankedCopy(actor, heldId),
                        outcome -> heldStatements.recordBlankedCopyResult(actor, heldId, outcome)))));
    }

    /**
     * Runs the work under an import slot. A busy server keeps the import queue's code and status,
     * with a message about the copy: the queue's own speaks of the reader's statement imports, and
     * the person asking here is staff, not the customer.
     */
    private <T> T gated(Callable<T> work) throws Exception {
        try {
            return concurrencyLimiter.runGated(work);
        } catch (ApiException e) {
            if (e.getCode() != ErrorCode.IMPORT_SYSTEM_BUSY) throw e;
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY, BUSY_MESSAGE);
        }
    }

    /**
     * Makes the copy and records what came of it: the check's result for a copy made, the kinds
     * named for one the privacy scan refused. A refusal before the check (a CSV, a locked file, text
     * that cannot be read) records nothing more: no copy was made or refused, and the access entry
     * written before the read stands on its own.
     */
    private BlankedCopy makeAndRecord(StaffStatementContent content, Consumer<BlankedCopyOutcome> record) {
        BlankedCopy copy;
        try {
            copy = blankedCopies.make(content);
        } catch (BlankedCopyRefusedException refused) {
            record.accept(BlankedCopyOutcome.refused(refused.kinds()));
            throw refused;
        }
        record.accept(BlankedCopyOutcome.of(copy));
        return copy;
    }

    private void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
    }
}
