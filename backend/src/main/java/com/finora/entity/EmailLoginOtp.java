package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Same hashed/single-use/short-TTL shape as {@link EmailVerificationToken}/{@link PasswordResetToken},
 *  plus attemptCount -- a 6-digit code has far less entropy than those tokens' 256-bit random
 *  value, so this bounds guesses against one issued code directly rather than relying on expiry
 *  alone. See docs/superpowers/specs/2026-09-22-otp-login-design.md. */
@Entity
@Table(name = "email_login_otps")
public class EmailLoginOtp {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String email;

    /** Which portal's account this code was issued for (see V221) -- the same email can back a
     *  separate USER-scope and ADMIN-scope account, so every lookup must be scoped by this, not
     *  just email, or a code minted for one account could invalidate or complete a login for the
     *  other. */
    @Column(name = "account_scope", nullable = false)
    private String accountScope;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAccountScope() { return accountScope; }
    public void setAccountScope(String accountScope) { this.accountScope = accountScope; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
