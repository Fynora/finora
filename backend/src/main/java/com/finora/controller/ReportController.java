package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReportDto;
import com.finora.security.CurrentUser;
import com.finora.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final CurrentUser currentUser;

    public ReportController(ReportService reportService, CurrentUser currentUser) {
        this.reportService = reportService;
        this.currentUser = currentUser;
    }

    @GetMapping("/months")
    public ApiResponse<List<String>> availableMonths() {
        return ApiResponse.ok(reportService.availableMonths(currentUser.id()));
    }

    @GetMapping
    public ApiResponse<ReportDto> forMonth(@RequestParam String month) {
        return ApiResponse.ok(reportService.forMonth(currentUser.id(), month));
    }

    @GetMapping("/income-trend")
    public ApiResponse<List<ReportService.IncomeTrendPoint>> incomeTrend() {
        return ApiResponse.ok(reportService.incomeTrend(currentUser.id()));
    }

    /** Income and expense per month, ending at {@code month} (the newest month with data when
     *  omitted). {@code months} is clamped to 1-12 rather than rejected: asking for more history
     *  than exists, or less than one month, has an obvious nearest answer. A malformed {@code month}
     *  or a non-numeric {@code months} is a 400 from GlobalExceptionHandler, the same response
     *  {@code /reports?month=} gives. */
    @GetMapping("/trend")
    public ApiResponse<List<ReportService.ReportTrendPoint>> monthlyTrend(@RequestParam(required = false) String month,
                                                                          @RequestParam(defaultValue = "6") int months) {
        YearMonth anchor = month == null || month.isBlank() ? null : YearMonth.parse(month);
        return ApiResponse.ok(reportService.trend(currentUser.id(), anchor, months));
    }
}
