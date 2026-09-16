package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
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

    /** @param messageId the persisted assistant row's id -- a client needs this to later call
     *                    {@code PATCH /chat/messages/{messageId}/feedback} on this specific reply. */
    public record ChatResponse(UUID conversationId, String reply, UUID messageId) {}

    /** One persisted turn, for {@code GET /chat/history} -- {@code role} is lowercase
     *  ("user"/"assistant"), matching the client's own {@code ChatTurn} shape directly rather than
     *  making every client re-map {@code ChatMessage.ROLE_USER}'s uppercase constant. {@code
     *  feedback} is null on a user turn (feedback is only ever recorded against an assistant
     *  reply) and null on an assistant turn nobody has rated yet. */
    public record ChatTurnDto(UUID id, String role, String content, String feedback) {}

    /** @param conversationId null when the caller has never sent Fyn a message -- {@code turns} is
     *                        empty in that case too, and the client shows its normal empty/
     *                        suggested-questions state, exactly as if this endpoint didn't exist. */
    public record ChatHistoryResponse(UUID conversationId, List<ChatTurnDto> turns) {}

    /** @param feedback one of {@code ChatMessage.FEEDBACK_HELPFUL}/{@code FEEDBACK_NOT_HELPFUL},
     *                  or null to clear a previously-given rating (tapping the same thumb twice
     *                  toggles it off, rather than every reply being permanently rated one way). */
    public record FeedbackRequest(String feedback) {}
}
