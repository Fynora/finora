package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AppIconDtos.AppIconDto;
import com.finora.dto.AppIconDtos.AppIconOptionDto;
import com.finora.dto.AppIconDtos.AppIconScheduleDto;
import com.finora.dto.AppIconDtos.AppIconScheduleSaveRequest;
import com.finora.security.CurrentUser;
import com.finora.service.AppIconService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface for the festival app icon schedule. Gated by SYSTEM_SETTINGS, the same permission
 * as {@link AdminFeatureFlagController}: choosing which icon every phone shows is the same class of
 * platform-wide operational configuration as flipping a flag. Class-level, so any endpoint added
 * later is gated by default. The rules live in {@link AppIconService}.
 */
@RestController
@RequestMapping("/api/v1/admin/app-icon")
@PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
public class AdminAppIconController {

    private final AppIconService service;
    private final CurrentUser currentUser;

    public AdminAppIconController(AppIconService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /**
     * The same answer the public GET /api/v1/app/icon gives, for the page's "Showing now" line. A
     * separate route because the public one tells browsers to reuse its answer for a minute, which
     * would show an admin the old icon right after they saved a change.
     */
    @GetMapping("/current")
    public ApiResponse<AppIconDto> currentAppIconForAdmin() {
        return ApiResponse.ok(service.current());
    }

    /** The icons the installed app bundles and an admin may schedule. */
    @GetMapping("/icons")
    public ApiResponse<List<AppIconOptionDto>> listAppIcons() {
        return ApiResponse.ok(service.icons());
    }

    @GetMapping("/schedule")
    public ApiResponse<List<AppIconScheduleDto>> listAppIconSchedule() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AppIconScheduleDto> createAppIconSchedule(@Valid @RequestBody AppIconScheduleSaveRequest request) {
        return ApiResponse.ok(service.create(currentUser.id(), request));
    }

    @PutMapping("/schedule/{id}")
    public ApiResponse<AppIconScheduleDto> updateAppIconSchedule(@PathVariable UUID id,
                                                                  @Valid @RequestBody AppIconScheduleSaveRequest request) {
        return ApiResponse.ok(service.update(currentUser.id(), id, request));
    }

    @DeleteMapping("/schedule/{id}")
    public ApiResponse<Void> deleteAppIconSchedule(@PathVariable UUID id) {
        service.delete(currentUser.id(), id);
        return ApiResponse.ok(null);
    }
}
