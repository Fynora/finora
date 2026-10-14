package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.OriginalViewDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.original.OriginalStatementViewService;
import com.finora.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The one way staff see a customer's real statement: page images in the browser, opened by an
 * Admin or Super Admin with a typed reason, for 30 minutes, for that person only. Nothing here
 * returns the file's bytes, sets an attachment disposition or lets a response be cached.
 *
 * <p>Each queue's opening restates its permission and the role pin in one expression -- a
 * method-level rule replaces the class rule, it does not add to it. Page fetches need only the
 * role: the view row they name was opened under the queue's rule, and belongs to the caller or is
 * a 404. Opening and rendering are CPU work on the request thread, so they take an import slot, as
 * the blanked copy does.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOriginalViewController {

    /** What a busy server says here; see {@link #gated}. */
    static final String BUSY_MESSAGE = "The server is busy reading statements. Try again in a minute.";

    private final OriginalStatementViewService views;
    private final CurrentUser currentUser;
    private final ImportConcurrencyLimiter concurrencyLimiter;

    public AdminOriginalViewController(OriginalStatementViewService views, CurrentUser currentUser,
                                       ImportConcurrencyLimiter concurrencyLimiter) {
        this.views = views;
        this.currentUser = currentUser;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/held-imports/{jobId}/original-view")
    public ResponseEntity<ApiResponse<OriginalViewDto.Opened>> openImport(
            @PathVariable UUID jobId, @RequestBody(required = false) OriginalViewDto.OpenRequest body) throws Exception {
        UUID viewer = currentUser.id();
        String reason = body == null ? null : body.reason();
        return noStore(ApiResponse.ok(OriginalViewDto.Opened.of(gated(() -> views.openImport(viewer, jobId, reason)))));
    }

    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/held-statements/{heldId}/original-view")
    public ResponseEntity<ApiResponse<OriginalViewDto.Opened>> openTrust(
            @PathVariable String heldId, @RequestBody(required = false) OriginalViewDto.OpenRequest body) throws Exception {
        UUID viewer = currentUser.id();
        String reason = body == null ? null : body.reason();
        return noStore(ApiResponse.ok(OriginalViewDto.Opened.of(gated(() -> views.openTrust(viewer, heldId, reason)))));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(value = "/original-views/{viewId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable UUID viewId, @PathVariable int pageNumber) throws Exception {
        UUID viewer = currentUser.id();
        byte[] png = gated(() -> views.page(viewer, viewId, pageNumber));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/original-views/{viewId}/csv")
    public ResponseEntity<ApiResponse<OriginalViewDto.CsvLines>> csv(@PathVariable UUID viewId) {
        UUID viewer = currentUser.id();
        return noStore(ApiResponse.ok(new OriginalViewDto.CsvLines(views.csv(viewer, viewId))));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Runs the work under an import slot, as {@code AdminBlankedCopyController.gated} does, with a
     *  busy message for staff rather than the import queue's own, which speaks to a customer. */
    private <T> T gated(Callable<T> work) throws Exception {
        try {
            return concurrencyLimiter.runGated(work);
        } catch (ApiException e) {
            if (e.getCode() != ErrorCode.IMPORT_SYSTEM_BUSY) throw e;
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY, BUSY_MESSAGE);
        }
    }
}
