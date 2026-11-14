package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.observability.jobs.BackgroundJobDto;
import com.finora.observability.jobs.BackgroundJobFailure;
import com.finora.observability.jobs.BackgroundJobsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin portal's Background jobs page: every scheduled job, its state, and a job's failures in
 * the last 30 days. Read-only diagnostics, so the same permission as System Health.
 */
@RestController
@RequestMapping("/api/v1/admin/background-jobs")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminBackgroundJobsController {

    private final BackgroundJobsService backgroundJobsService;

    public AdminBackgroundJobsController(BackgroundJobsService backgroundJobsService) {
        this.backgroundJobsService = backgroundJobsService;
    }

    @GetMapping
    public ApiResponse<List<BackgroundJobDto>> list() {
        return ApiResponse.ok(backgroundJobsService.list());
    }

    /** The key is a query parameter, not a path segment: a GitHub workflow's key holds ":" and "/". */
    @GetMapping("/failures")
    public ApiResponse<List<BackgroundJobFailure>> failures(@RequestParam String key) {
        return ApiResponse.ok(backgroundJobsService.failures(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such background job")));
    }
}
