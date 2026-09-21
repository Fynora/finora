package com.finora.entity;

import com.finora.integrations.google.GmailConnection;
import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). See V98's own
 *  migration comment for the full design -- 0-or-1 row per user, {@link #enabled} only flips true
 *  once {@link com.finora.service.AdminMfaService#confirm} has verified a real generated code. */
@Entity
@Table(name = "admin_totp_credentials")
public class AdminTotpCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    @Column(name = "encryption_key_id", nullable = false)
    private String encryptionKeyId;

    @Column(nullable = false)
    private boolean enabled = false;

    /**
     * Highest TOTP time step a code has been accepted for (V218), null before the first. Mapped
     * read-only ({@code updatable = false}) on purpose: it is written ONLY by
     * {@code AdminTotpCredentialRepository#claimStep}, a conditional UPDATE. If a normal
     * {@code save()} could write it too, an entity loaded before another request claimed a step
     * would write its stale value back over the claim and re-open the replay window.
     */
    @Column(name = "last_used_step", insertable = false, updatable = false)
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    /** Reassembles the stored halves into the shape {@code EncryptionService.decrypt} takes --
     *  same convention as {@link GmailConnection#credential()}. */
    public EncryptedValue secret() {
        return new EncryptedValue(encryptionKeyId, encryptedSecret);
    }

    /** Takes {@link EncryptedValue} rather than two strings so a caller cannot pair a ciphertext
     *  with the wrong key id -- same convention as {@link GmailConnection#storeCredential}. */
    public void storeSecret(EncryptedValue value) {
        this.encryptedSecret = value.ciphertext();
        this.encryptionKeyId = value.keyId();
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() { return enabled; }

    /** Null until a code has been accepted. See the field's doc for why there is no setter. */
    public Long getLastUsedStep() { return lastUsedStep; }

    public void markEnabled() {
        this.enabled = true;
        this.enabledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getEnabledAt() { return enabledAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
