package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DashboardRangeSummaryDto;
import com.finora.dto.DashboardRangeType;
import com.finora.dto.DashboardSummaryDto;
import com.finora.security.CurrentUser;
import com.finora.service.DashboardRangeService;
import com.finora.service.DashboardService;
import com.finora.util.EnumParsing;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardRangeService dashboardRangeService;
    private final CurrentUser currentUser;

    public DashboardController(DashboardService dashboardService, DashboardRangeService dashboardRangeService,
                                CurrentUser currentUser) {
        this.dashboardService = dashboardService;
        this.dashboardRangeService = dashboardRangeService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> summary() {
        return ApiResponse.ok(dashboardService.summarize(currentUser.id()));
    }

    /**
     * Range-based KPI cards (Total Balance/Income/Expenses/Net Savings/Savings Rate) and the Cash
     * Flow chart -- a separate period model from {@link #summary()} above, which stays on the
     * single reporting month (see {@code DashboardRangeService}'s own doc comment for why the two
     * are deliberately not unified into one endpoint).
     *
     * <p>{@code rangeType} defaults to {@code LAST_6_MONTHS} when omitted. {@code startDate} and
     * {@code endDate} (ISO {@code YYYY-MM-DD}) are required only for {@code CUSTOM} and ignored
     * otherwise.
     */
    @GetMapping("/range-summary")
    public ApiResponse<DashboardRangeSummaryDto> rangeSummary(
            @RequestParam(required = false) String rangeType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        DashboardRangeType type = rangeType == null || rangeType.isBlank()
                ? DashboardRangeType.LAST_6_MONTHS
                : EnumParsing.parse(DashboardRangeType.class, rangeType, "rangeType");
        LocalDate start = startDate == null || startDate.isBlank() ? null : LocalDate.parse(startDate);
        LocalDate end = endDate == null || endDate.isBlank() ? null : LocalDate.parse(endDate);
        return ApiResponse.ok(dashboardRangeService.summarize(currentUser.id(), type, start, end));
    }
}
