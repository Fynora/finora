package com.finora.activation;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate 2a: what the apps report about a user's way to a first statement. The user is always the
 * signed-in caller, never a parameter.
 */
@RestController
@RequestMapping("/api/v1/activation")
public class ActivationController {

    private final ImportFunnelService importFunnelService;
    private final CurrentUser currentUser;

    public ActivationController(ImportFunnelService importFunnelService, CurrentUser currentUser) {
        this.importFunnelService = importFunnelService;
        this.currentUser = currentUser;
    }

    @PostMapping("/funnel-events")
    public ApiResponse<Void> funnelEvent(@Valid @RequestBody ActivationDto.FunnelEventRequest request) {
        importFunnelService.record(currentUser.id(), request.event());
        return ApiResponse.ok(null);
    }
}
