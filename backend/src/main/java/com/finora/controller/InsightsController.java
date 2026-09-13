package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.FynInsightsNarrationDto;
import com.finora.dto.InsightsDto;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.security.CurrentUser;
import com.finora.service.EntitlementService;
import com.finora.service.FynInsightsNarrationService;
import com.finora.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final FynInsightsNarrationService fynInsightsNarrationService;
    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public InsightsController(InsightsService insightsService,
                               FynInsightsNarrationService fynInsightsNarrationService,
                               EntitlementService entitlementService, CurrentUser currentUser) {
        this.insightsService = insightsService;
        this.fynInsightsNarrationService = fynInsightsNarrationService;
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<InsightsDto> insights(@RequestParam(required = false) String month) {
        return ApiResponse.ok(insightsService.build(currentUser.id(), month));
    }

    /** Fyn Phase 3 (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6 Phase 3)
     *  -- its own endpoint, deliberately: a failure here (guard refusal, Anthropic outage, cost
     *  cap, or simply nothing worth narrating) must never affect {@link #insights}, which this
     *  calls nothing in common with beyond reading the same underlying data independently. */
    @GetMapping("/narration")
    public ApiResponse<FynInsightsNarrationDto> narration(@RequestParam(required = false) String month) {
        requireFynInsights();
        return ApiResponse.ok(new FynInsightsNarrationDto(
                fynInsightsNarrationService.narrate(currentUser.id(), month)));
    }

    /** Same per-request, no-cache posture as {@code AnalyticsController.requireAdvancedReports} --
     *  see that method's own doc for why. */
    private void requireFynInsights() {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_INSIGHTS)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
    }
}
