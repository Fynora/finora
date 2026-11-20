package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Same shape as {@link PasswordResetToken} / {@link AccountReactivationToken}, for the identical
 *  reason -- see V93's own comment. */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Sent by sign-up. Usable only from a session of the account it was sent for. */
    public static final String ORIGIN_SIGN_UP = "SIGN_UP";
    /** Sent because a Google sign-in met an account whose email was never proven. */
    public static final String ORIGIN_GOOGLE = "GOOGLE";
    /** Sent because an Apple sign-in met an account whose email was never proven. */
    public static final String ORIGIN_APPLE = "APPLE";

    /** Which flow sent this link (V329). Decides what opening it requires and what it changes --
     *  see AuthService.verifyEmail. */
    @Column(name = "origin", nullable = false, length = 20)
    private String origin = ORIGIN_SIGN_UP;

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    /** True for a link sent by sign-up rather than by a Google or Apple sign-in attempt. */
    public boolean isFromSignUp() { return ORIGIN_SIGN_UP.equals(origin); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
