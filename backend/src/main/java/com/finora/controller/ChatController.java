package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.FynChatDtos.ChatRequest;
import com.finora.dto.FynChatDtos.ChatResponse;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.security.CurrentUser;
import com.finora.service.EntitlementService;
import com.finora.service.FynChatOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fyn Phase 4 chat -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6 Phase 4.
 * JWT-scoped like every other user endpoint (plan §2): no new authz surface, the tool-calling loop
 * runs inside this same authenticated request.
 */
@RestController
@RequestMapping("/api/v1/fyn")
public class ChatController {

    private final FynChatOrchestrationService orchestrationService;
    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public ChatController(FynChatOrchestrationService orchestrationService,
                           EntitlementService entitlementService, CurrentUser currentUser) {
        this.orchestrationService = orchestrationService;
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_CHAT)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
        var result = orchestrationService.sendMessage(currentUser.id(), request.conversationId(), request.message());
        return ApiResponse.ok(new ChatResponse(result.conversationId(), result.reply()));
    }
}
