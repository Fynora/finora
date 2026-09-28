package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.dto.StatementRefreshRequest;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.refresh.StatementRefreshService;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Re-reads one of the user's statements with today's parser and applies what changed -- see
 * {@link StatementRefreshService}. Off unless {@code app.statement-refresh.apply.enabled}: the
 * button and the summary screen are step 5.
 *
 * <p>The password travels in the body, never the URL, exactly as {@code /reimport}'s does, and is
 * used for this re-read only. Gated like an upload: a refresh parses the whole file again.
 */
@RestController
@RequestMapping("/api/v1/statement-imports")
public class StatementRefreshController {

    private final StatementRefreshService refreshService;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;

    public StatementRefreshController(StatementRefreshService refreshService,
                                      ImportConcurrencyLimiter concurrencyLimiter, CurrentUser currentUser) {
        this.refreshService = refreshService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<StatementRefreshOutcome> refreshStatement(@PathVariable UUID id,
                                                        @RequestBody(required = false) StatementRefreshRequest request)
            throws Exception {
        UUID userId = currentUser.id();
        String password = request == null ? null : request.password();
        boolean savePassword = request != null && Boolean.TRUE.equals(request.savePassword());
        return ApiResponse.ok(concurrencyLimiter.runGated(() -> refreshService.refresh(userId, id, password, savePassword)));
    }
}
