package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.FeatureUsageDtos.ViewCountResponse;
import com.finora.security.CurrentUser;
import com.finora.service.FeatureUsageService;
import org.springframework.web.bind.annotation.*;

/** Real per-user, per-feature view counts -- e.g. the Billing page's "Smart Insights" usage
 *  tile, which used to show a hardcoded "142" with nothing behind it. */
@RestController
@RequestMapping("/api/v1/usage")
public class FeatureUsageController {

    private final FeatureUsageService featureUsageService;
    private final CurrentUser currentUser;

    public FeatureUsageController(FeatureUsageService featureUsageService, CurrentUser currentUser) {
        this.featureUsageService = featureUsageService;
        this.currentUser = currentUser;
    }

    @PostMapping("/{feature}/view")
    public ApiResponse<Void> recordView(@PathVariable String feature) {
        featureUsageService.recordView(currentUser.id(), feature);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{feature}/view-count")
    public ApiResponse<ViewCountResponse> viewCount(@PathVariable String feature) {
        return ApiResponse.ok(new ViewCountResponse(featureUsageService.viewCount(currentUser.id(), feature)));
    }
}
