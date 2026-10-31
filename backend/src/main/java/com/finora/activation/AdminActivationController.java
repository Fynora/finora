package com.finora.activation;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The activation page (Gate 2a spec §6). Counts only, behind the same permission as the other
 * platform-wide analytics. See ActivationFunnelService.
 *
 * <p>Release markers (V303) are read with the report. Adding or removing one is a change to what
 * every admin sees, so it also needs SYSTEM_SETTINGS, the permission the other platform-wide
 * configuration pages use. A method-level {@code @PreAuthorize} replaces the class-level one rather
 * than adding to it, so each write names both.
 */
@RestController
@RequestMapping("/api/v1/admin/analytics/activation")
@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")
public class AdminActivationController {

    private final ActivationFunnelService activationFunnelService;
    private final ReleaseMarkerService releaseMarkerService;
    private final CurrentUser currentUser;

    public AdminActivationController(ActivationFunnelService activationFunnelService,
                                     ReleaseMarkerService releaseMarkerService, CurrentUser currentUser) {
        this.activationFunnelService = activationFunnelService;
        this.releaseMarkerService = releaseMarkerService;
        this.currentUser = currentUser;
    }

    /** {@code from} and {@code to} bound the answers only; by default the last 90 days to today. */
    @GetMapping
    public ApiResponse<ActivationReportDto> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(activationFunnelService.report(from, to));
    }

    /** A new release marker: the IST day it went live and a short label. */
    @Schema(name = "ActivationReleaseMarkerRequest")
    public record ReleaseMarkerRequest(LocalDate date, String label) {}

    @Schema(name = "ActivationReleaseMarkerSaved")
    public record ReleaseMarkerSaved(long id, LocalDate date, String label) {}

    @PostMapping("/markers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW') and hasAuthority('SYSTEM_SETTINGS')")
    public ApiResponse<ReleaseMarkerSaved> createReleaseMarker(@RequestBody ReleaseMarkerRequest request) {
        ReleaseMarkerService.Saved saved = releaseMarkerService.create(currentUser.id(), request.date(), request.label());
        return ApiResponse.ok(new ReleaseMarkerSaved(saved.id(), saved.date(), saved.label()));
    }

    @DeleteMapping("/markers/{id}")
    @PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW') and hasAuthority('SYSTEM_SETTINGS')")
    public ApiResponse<Void> deleteReleaseMarker(@PathVariable long id) {
        releaseMarkerService.delete(currentUser.id(), id);
        return ApiResponse.ok(null);
    }
}
