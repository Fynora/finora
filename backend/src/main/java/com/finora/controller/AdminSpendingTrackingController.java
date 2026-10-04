package com.finora.controller;

import com.finora.dto.AdminDtos.SpendingTrackingBreakdown;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminSpendingTrackingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How users kept track of their spending before Fynora -- the answers to the required setup
 * question (V256). Behind the same permission as the other platform-wide analytics
 * (PLATFORM_ANALYTICS_VIEW): counts only, never who answered what. See
 * AdminSpendingTrackingService.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics/spending-tracking")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminSpendingTrackingController {

    private final AdminSpendingTrackingService adminSpendingTrackingService;

    public AdminSpendingTrackingController(AdminSpendingTrackingService adminSpendingTrackingService) {
        this.adminSpendingTrackingService = adminSpendingTrackingService;
    }

    @GetMapping
    public ApiResponse<SpendingTrackingBreakdown> spendingTrackingBreakdown() {
        return ApiResponse.ok(adminSpendingTrackingService.breakdown());
    }
}
