package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.StatementRefreshDtos.RefreshAllResult;
import com.finora.dto.StatementRefreshDtos.RefreshOverview;
import com.finora.dto.StatementRefreshDtos.RefreshRunDetail;
import com.finora.imports.ImportConcurrencyLimiter;
import com.finora.imports.refresh.StatementRefreshUserService;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Statement refresh, step 5: the banner's data, the one tap that updates the statements it offers,
 * and the "what changed" summary of one refresh. Refreshing one statement -- and a protected PDF's
 * password -- stays on {@code POST /statement-imports/{id}/refresh}.
 */
@RestController
@RequestMapping("/api/v1/statement-refresh")
public class StatementRefreshOverviewController {

    private final StatementRefreshUserService userService;
    private final ImportConcurrencyLimiter concurrencyLimiter;
    private final CurrentUser currentUser;

    public StatementRefreshOverviewController(StatementRefreshUserService userService,
                                              ImportConcurrencyLimiter concurrencyLimiter, CurrentUser currentUser) {
        this.userService = userService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<RefreshOverview> statementRefreshOverview() {
        return ApiResponse.ok(userService.overview(currentUser.id()));
    }

    /** Gated like an upload: every statement is parsed again. */
    @PostMapping("/apply")
    public ApiResponse<RefreshAllResult> refreshAllStatements() throws Exception {
        UUID userId = currentUser.id();
        return ApiResponse.ok(concurrencyLimiter.runGated(() -> userService.refreshAll(userId)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RefreshRunDetail> statementRefreshRun(@PathVariable UUID runId) {
        return ApiResponse.ok(userService.run(currentUser.id(), runId));
    }
}
