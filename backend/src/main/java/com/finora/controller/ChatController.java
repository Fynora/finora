package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.FynChatDtos.ChatHistoryResponse;
import com.finora.dto.FynChatDtos.ChatRequest;
import com.finora.dto.FynChatDtos.ChatResponse;
import com.finora.dto.FynChatDtos.FeedbackRequest;
import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.security.CurrentUser;
import com.finora.service.EntitlementService;
import com.finora.service.FynChatOrchestrationService;
import com.finora.service.FynScreenshotOcrService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

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
    private final FynScreenshotOcrService screenshotOcrService;

    public ChatController(FynChatOrchestrationService orchestrationService,
                           EntitlementService entitlementService, CurrentUser currentUser,
                           FynScreenshotOcrService screenshotOcrService) {
        this.orchestrationService = orchestrationService;
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
        this.screenshotOcrService = screenshotOcrService;
    }

    /** The caller's most recently updated conversation, in full -- a client calls this on mount so
     *  navigating away and back resumes where the conversation left off instead of starting blank.
     *  Same {@code FYN_CHAT} entitlement as {@link #chat}, but no availability check: reading
     *  turns Fyn already answered doesn't need the LLM to be reachable right now. */
    @GetMapping("/chat/history")
    public ApiResponse<ChatHistoryResponse> history() {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_CHAT)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
        return ApiResponse.ok(orchestrationService.latestConversationHistory(currentUser.id()));
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_CHAT)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
        var result = orchestrationService.sendMessage(currentUser.id(), request.conversationId(), request.message());
        return ApiResponse.ok(new ChatResponse(result.conversationId(), result.reply(), result.messageId()));
    }

    /** Thumbs up/down on one of Fyn's own replies -- {@code feedback} is one of {@code
     *  ChatMessage.FEEDBACK_HELPFUL}/{@code FEEDBACK_NOT_HELPFUL}, or null to clear a rating (a
     *  second tap on the same thumb toggles it off). Same {@code FYN_CHAT} entitlement as every
     *  other endpoint here; ownership/role validity is {@link FynChatOrchestrationService
     *  #setMessageFeedback}'s own job, not duplicated here. */
    @PatchMapping("/chat/messages/{messageId}/feedback")
    public ApiResponse<Void> setFeedback(@PathVariable UUID messageId, @RequestBody FeedbackRequest request) {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_CHAT)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
        orchestrationService.setMessageFeedback(currentUser.id(), messageId, request.feedback());
        return ApiResponse.ok(null, "Feedback saved");
    }

    /**
     * A screenshot attached to a chat turn -- OCR'd server-side by {@link FynScreenshotOcrService}
     * into plain text, then sent through the exact same {@link FynChatOrchestrationService#sendMessage}
     * every other turn uses. No change to that orchestration loop itself: as far as it's concerned,
     * this is just a longer user message. Same {@code FYN_CHAT} entitlement as {@link #chat} --
     * this is chat, not a separate feature, just a richer input to it.
     */
    @PostMapping(value = "/chat/screenshot", consumes = "multipart/form-data")
    public ApiResponse<ChatResponse> chatWithScreenshot(@RequestParam("image") MultipartFile image,
                                                         @RequestParam(value = "message", required = false) String message,
                                                         @RequestParam(value = "conversationId", required = false) UUID conversationId) {
        if (!entitlementService.hasEntitlement(currentUser.id(), FeatureEntitlement.FYN_CHAT)) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED);
        }
        // Request-shape validation before the availability check -- a wrong file type should
        // always come back as "fix your upload," not "OCR is down," even when OCR genuinely is
        // down (e.g. this environment has no tesseract binary at all).
        try {
            screenshotOcrService.validate(image, message);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!FynScreenshotOcrService.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Reading screenshots isn't available right now -- please type your question instead.");
        }
        String augmentedMessage;
        try {
            augmentedMessage = screenshotOcrService.describeForChat(image, message);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Couldn't read that screenshot right now -- please try again or type your question instead.");
        }
        var result = orchestrationService.sendMessage(currentUser.id(), conversationId, augmentedMessage);
        return ApiResponse.ok(new ChatResponse(result.conversationId(), result.reply(), result.messageId()));
    }
}
