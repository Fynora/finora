package com.finora.imports.send;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Gate 2b spec §7: what the phone and the public send page are told. Never any account data. */
public final class SendDto {

    private SendDto() {
    }

    /** A new code, shown once on the phone. Only its hash is stored. */
    @Schema(name = "StatementSendCodeIssued")
    public record CodeIssued(String code, Instant expiresAt) {}

    /**
     * The phone's latest code: NONE (no live code), WAITING (live, no file yet), RECEIVED (a file
     * arrived; {@code importJobId} is its job) or EXPIRED (ran out unused).
     */
    @Schema(name = "StatementSendCodeStatus")
    public record CodeStatus(String state, UUID importJobId) {}

    /** The public page's answer. Deliberately nothing but this: no job id, no name, no email. */
    @Schema(name = "StatementSendReceived")
    public record Received(boolean received) {}
}
