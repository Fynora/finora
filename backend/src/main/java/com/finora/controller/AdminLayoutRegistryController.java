package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.entity.RegisteredLayout;
import com.finora.exception.ApiException;
import com.finora.imports.LayoutCurationService;
import com.finora.imports.LayoutCurationService.ProfileView;
import com.finora.imports.LayoutCurationService.RegistryEntry;
import com.finora.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The layout registry's operator surface (V243): the review queue, curating a layout's name and
 * support status, and grouping layouts into versioned profiles.
 *
 * <p>Reads are open to {@code PLATFORM_DIAGNOSTICS_VIEW} (whoever already sees Layout Intelligence)
 * or {@code LAYOUT_REGISTRY_MANAGE}; every write needs {@code LAYOUT_REGISTRY_MANAGE}. Each method
 * carries its own complete expression rather than layering on a class-level one, because a
 * method-level {@code @PreAuthorize} replaces the class-level one instead of adding to it.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/layout-registry")
public class AdminLayoutRegistryController {

    private static final String READ = "hasAnyAuthority('PLATFORM_DIAGNOSTICS_VIEW', 'LAYOUT_REGISTRY_MANAGE')";
    private static final String WRITE = "hasAuthority('LAYOUT_REGISTRY_MANAGE')";

    private final LayoutCurationService curationService;
    private final CurrentUser currentUser;

    public AdminLayoutRegistryController(LayoutCurationService curationService, CurrentUser currentUser) {
        this.curationService = curationService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<RegistryEntry>> registry() {
        return ApiResponse.ok(curationService.registry());
    }

    @GetMapping("/review-queue")
    @PreAuthorize(READ)
    public ApiResponse<List<RegistryEntry>> reviewQueue() {
        return ApiResponse.ok(curationService.reviewQueue());
    }

    @PostMapping("/{fingerprint}/review/resolve")
    @PreAuthorize(WRITE)
    public ApiResponse<RegistryEntry> resolveReview(@PathVariable String fingerprint) {
        return ApiResponse.ok(curationService.resolveReview(currentUser.id(), fingerprint), "Review resolved");
    }

    /** Body: {@code {"name": "...", "status": "SUPPORTED"}}, either key optional; {@code "name": null}
     *  clears the name. */
    @PatchMapping("/{fingerprint}")
    @PreAuthorize(WRITE)
    public ApiResponse<RegistryEntry> update(@PathVariable String fingerprint, @RequestBody Map<String, Object> body) {
        boolean nameProvided = body.containsKey("name");
        Object rawName = body.get("name");
        if (rawName != null && !(rawName instanceof String)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "name must be a string or null");
        }
        RegisteredLayout.Status status = null;
        Object rawStatus = body.get("status");
        if (rawStatus != null) {
            try {
                status = RegisteredLayout.Status.valueOf(rawStatus.toString());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown layout status " + rawStatus);
            }
        }
        return ApiResponse.ok(curationService.update(currentUser.id(), fingerprint, (String) rawName, status,
                nameProvided), "Layout updated");
    }

    @GetMapping("/profiles")
    @PreAuthorize(READ)
    public ApiResponse<List<ProfileView>> profiles() {
        return ApiResponse.ok(curationService.profiles());
    }

    @PostMapping("/profiles")
    @PreAuthorize(WRITE)
    public ApiResponse<ProfileView> createProfile(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(curationService.createProfile(currentUser.id(), body.get("name")), "Profile created");
    }

    @PatchMapping("/profiles/{profileId}")
    @PreAuthorize(WRITE)
    public ApiResponse<Void> renameProfile(@PathVariable UUID profileId, @RequestBody Map<String, String> body) {
        curationService.renameProfile(currentUser.id(), profileId, body.get("name"));
        return ApiResponse.ok(null, "Profile renamed");
    }

    /** Body: {@code {"profileId": "<uuid>"}}. The layout becomes the profile's next version. */
    @PutMapping("/{fingerprint}/profile")
    @PreAuthorize(WRITE)
    public ApiResponse<RegistryEntry> linkToProfile(@PathVariable String fingerprint,
                                                    @RequestBody Map<String, String> body) {
        UUID profileId;
        try {
            profileId = UUID.fromString(String.valueOf(body.get("profileId")));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "profileId must be a profile id");
        }
        return ApiResponse.ok(curationService.linkToProfile(currentUser.id(), fingerprint, profileId),
                "Layout added to profile");
    }

    @DeleteMapping("/{fingerprint}/profile")
    @PreAuthorize(WRITE)
    public ApiResponse<RegistryEntry> unlinkFromProfile(@PathVariable String fingerprint) {
        return ApiResponse.ok(curationService.unlinkFromProfile(currentUser.id(), fingerprint),
                "Layout removed from profile");
    }
}
