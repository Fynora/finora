package com.finora.activation;

import com.finora.dto.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The activation page (Gate 2a spec §6). Counts only, behind the same permission as the other
 * platform-wide analytics. See ActivationFunnelService.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics/activation")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminActivationController {

    private final ActivationFunnelService activationFunnelService;

    public AdminActivationController(ActivationFunnelService activationFunnelService) {
        this.activationFunnelService = activationFunnelService;
    }

    /** {@code from} and {@code to} bound the answers only; by default the last 90 days to today. */
    @GetMapping
    public ApiResponse<ActivationReportDto> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(activationFunnelService.report(from, to));
    }
}
