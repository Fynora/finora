package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BlankedCopyDto;
import com.finora.exception.ApiException;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.blanked.BlankedCopyService;
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
 * parse. A busy server refuses at once with the import queue's own 503, before the audit row is
 * written and before any bytes are read.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBlankedCopyController {

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
        return ApiResponse.ok(BlankedCopyDto.Response.of(concurrencyLimiter.runGated(
                () -> blankedCopies.make(heldImports.contentForBlankedCopy(actor, jobId)))));
    }

    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")
    @GetMapping("/held-statements/{heldId}/blanked-copy")
    public ApiResponse<BlankedCopyDto.Response> heldStatement(@PathVariable String heldId) throws Exception {
        requireEnabled();
        UUID actor = currentUser.id();
        return ApiResponse.ok(BlankedCopyDto.Response.of(concurrencyLimiter.runGated(
                () -> blankedCopies.make(heldStatements.contentForBlankedCopy(actor, heldId)))));
    }

    private void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
    }
}
