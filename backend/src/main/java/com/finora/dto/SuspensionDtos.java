package com.finora.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** The suspended screen's API (privacy workstream 10); see AccountSuspensionService. */
public final class SuspensionDtos {

    private SuspensionDtos() {}

    /**
     * @param suspendedAt   when the current suspension began; null only for an account suspended
     *                      before V309 whose start time could not be found in the audit log
     * @param reason        TERMS_VIOLATION, FRAUD or SECURITY_RISK; null when not yet recorded
     * @param reasonText    the ground in plain words, always present
     * @param message       the admin's message to the customer, or null
     * @param storePlatform "IOS" or "ANDROID" when Plus is paid through a store and still renews, or null
     * @param signInMethod  PASSWORD, GOOGLE or APPLE: how the screen asks them to confirm it is them
     *                      before downloading their data
     * @param requests      this account's appeals and deletion requests, newest first
     */
    public record SuspensionStatusDto(Instant suspendedAt, String reason, String reasonText, String message,
                                      String storePlatform, String signInMethod, List<SuspensionRequestDto> requests) {}

    /** @param type APPEAL or DELETION; @param status the ticket's status (OPEN, IN_PROGRESS, RESOLVED, CLOSED) */
    public record SuspensionRequestDto(String type, String ticketNumber, String status, Instant createdAt) {}

    /** @param type APPEAL or DELETION; @param text required for an appeal, optional for a deletion request */
    public record CreateSuspensionRequest(@NotBlank(message = "Choose APPEAL or DELETION") String type, String text) {}
}
