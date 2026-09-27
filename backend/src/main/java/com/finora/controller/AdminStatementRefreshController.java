package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.refresh.StatementRefreshSummaryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The statement refresh dry run's results, totalled -- see StatementRefreshSummaryService. */
@RestController
@RequestMapping("/api/v1/admin/statement-refresh")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminStatementRefreshController {

    private final StatementRefreshSummaryService summaryService;

    public AdminStatementRefreshController(StatementRefreshSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** @param parserVersion a build's short commit; defaults to the running build */
    @GetMapping("/summary")
    public ApiResponse<StatementRefreshSummaryService.RefreshSummary> summary(@RequestParam(required = false) String parserVersion) {
        return ApiResponse.ok(summaryService.summary(parserVersion));
    }
}
