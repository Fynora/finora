package com.finora.controller;

import com.finora.dto.AdminDtos.LearningEventRow;
import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.service.AdminLearningStatsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide Learning Engine stats for the admin console (frontend-admin/). Reuses
 *  MERCHANT_MANAGE rather than a new permission -- Learning Engine data is the same underlying
 *  merchant-category-confirmation domain Merchant Intelligence already gates, not a separate
 *  capability (same reasoning AdminUserRuleController used to reuse RULE_MANAGE). Per-user
 *  learning visibility (a specific account's timeline/summary) is a separate controller,
 *  AdminUserLearningController -- this one is read-only and never scoped to one user. */
@RestController
@RequestMapping("/api/v1/admin/learning")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminLearningStatsController {

    private final AdminLearningStatsService adminLearningStatsService;

    public AdminLearningStatsController(AdminLearningStatsService adminLearningStatsService) {
        this.adminLearningStatsService = adminLearningStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<LearningPlatformStatsDto> stats() {
        return ApiResponse.ok(adminLearningStatsService.platformStats());
    }

    /** The newest learning events across customer accounts, with each customer's email -- an
     *  admin read of many customers' data, recorded by AdminReadAuditPolicy. Also needs USER_VIEW:
     *  MERCHANT_MANAGE alone is held by roles that may not see who a customer is (OPS), and a
     *  method-level rule replaces the class-level one, so both are named here. */
    @PreAuthorize("hasAuthority('MERCHANT_MANAGE') and hasAuthority('USER_VIEW')")
    @GetMapping("/events")
    public ApiResponse<PagedResponse<LearningEventRow>> events(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(adminLearningStatsService.recentEvents(page, size));
    }
}
