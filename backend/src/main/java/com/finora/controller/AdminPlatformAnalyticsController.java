package com.finora.controller;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminPlatformAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/** Platform-wide spend analytics for the admin console (admin-portal/) -- for one period, top
 *  categories and top merchants by how many customers spent there, with spend per customer and a
 *  comparison with the period before. {@code from} is the first month ({@code YYYY-MM}) and
 *  {@code months} how many (1 to 12, default 1); no {@code from} means all time. See
 *  AdminPlatformAnalyticsService's class comment for why this needs a two-step aggregation.
 *  Gated by its own permission (PLATFORM_ANALYTICS_VIEW), separate from PLATFORM_STATS_VIEW's
 *  basic usage counts on AdminStatsController -- see V30's migration comment for why. */
@RestController
@RequestMapping("/api/v1/admin/analytics/platform")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminPlatformAnalyticsController {

    private final AdminPlatformAnalyticsService adminPlatformAnalyticsService;

    public AdminPlatformAnalyticsController(AdminPlatformAnalyticsService adminPlatformAnalyticsService) {
        this.adminPlatformAnalyticsService = adminPlatformAnalyticsService;
    }

    @GetMapping
    public ApiResponse<PlatformAnalyticsDto> analytics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @RequestParam(defaultValue = "1") int months) {
        return ApiResponse.ok(adminPlatformAnalyticsService.platformAnalytics(from, months));
    }
}
