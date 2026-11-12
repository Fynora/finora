package com.finora.invite;

import jakarta.validation.constraints.NotNull;

public final class InvitePromptDto {

    private InvitePromptDto() {}

    /** An unknown event name fails deserialization and is refused with 400. */
    public record InvitePromptEventRequest(@NotNull InvitePromptEvent event) {}

    /**
     * {@code show}: whether to show the card for the import(s) just confirmed.
     * {@code shownRecently}: whether the card was shown within the last
     * {@link InvitePromptService#RECENT_DAYS} days. The mobile app does not ask for a store rating
     * then, so the two never land on one person back to back.
     */
    public record InvitePromptResponse(boolean show, boolean shownRecently) {}
}
