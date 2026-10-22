package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.refresh.AdminStatementCorrectionService;
import com.finora.imports.refresh.AdminStatementCorrectionService.AppliedCorrection;
import com.finora.imports.refresh.AdminStatementCorrectionService.WaitingCorrection;
import com.finora.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statement corrections an admin can apply for a user -- see {@link AdminStatementCorrectionService}.
 *
 * <p>{@code IMPORT_TRIAGE_MANAGE}, the permission that already lets an admin reprocess a user's held
 * import: both re-run the import pipeline over a user's stored statement and change what lands in
 * their account. Class-level, so a new endpoint added here is gated by default.
 */
@RestController
@RequestMapping("/api/v1/admin/statement-corrections")
@PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE')")
public class AdminStatementCorrectionController {

    private final AdminStatementCorrectionService service;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;

    public AdminStatementCorrectionController(AdminStatementCorrectionService service,
                                              ImportConcurrencyLimiter concurrencyLimiter,
                                              CurrentUser currentUser) {
        this.service = service;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
    }

    /** The user's statements with a checked correction waiting. */
    @GetMapping("/users/{userId}")
    public ApiResponse<List<WaitingCorrection>> waitingCorrections(@PathVariable UUID userId) {
        return ApiResponse.ok(service.waiting(userId));
    }

    /** Applies one statement's waiting correction. {@code reason} (15 to 500 characters) is required
     *  and audited. Gated like an upload: the statement is parsed again. */
    @PostMapping("/statements/{statementId}/apply")
    public ApiResponse<AppliedCorrection> applyCorrection(@PathVariable UUID statementId,
                                                          @RequestBody(required = false) Map<String, String> body)
            throws Exception {
        UUID adminId = currentUser.id();
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(concurrencyLimiter.runGated(() -> service.apply(adminId, statementId, reason)));
    }
}
