package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Fyn Phase 4 chat -- docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6 Phase 4. */
public class FynChatDtos {

    /** @param conversationId null to start a new conversation, otherwise an existing one owned by
     *                        the caller.
     *  @param message        the user's turn. Size-bounded the same way any other free-text input
     *                        this API accepts is -- not a Fyn-specific limit, just the standing
     *                        convention (see e.g. HeldStatementService's notes fields). */
    public record ChatRequest(
            UUID conversationId,
            @NotBlank(message = "Message is required") @Size(max = 2000, message = "Message is too long") String message) {}

    public record ChatResponse(UUID conversationId, String reply) {}
}
