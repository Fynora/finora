package com.finora.entity;

import com.finora.security.crypto.EncryptedValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A protected PDF's password, saved because the user agreed to it -- see V240. Owned by a queued
 * import job until the upload is confirmed, then by each statement that upload produced.
 *
 * <p>Holds ciphertext only. {@link #credential()} hands it to {@code EncryptionService.decrypt};
 * nothing on this class ever sees the password itself, and it has no {@code toString}.
 */
@Entity
@Table(name = "statement_passwords")
public class StatementPassword {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "import_job_id")
    private UUID importJobId;

    @Column(name = "statement_import_id")
    private UUID statementImportId;

    @Column(name = "encrypted_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(name = "encryption_key_id", nullable = false, length = 64)
    private String encryptionKeyId;

    @Column(name = "consent_version", nullable = false, length = 32)
    private String consentVersion;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StatementPassword() {}

    private StatementPassword(UUID userId, UUID importJobId, UUID statementImportId, EncryptedValue value,
                              String consentVersion, Instant consentedAt) {
        this.userId = userId;
        this.importJobId = importJobId;
        this.statementImportId = statementImportId;
        this.encryptedPassword = value.ciphertext();
        this.encryptionKeyId = value.keyId();
        this.consentVersion = consentVersion;
        this.consentedAt = consentedAt;
    }

    public static StatementPassword forJob(UUID userId, UUID importJobId, EncryptedValue value,
                                           String consentVersion, Instant consentedAt) {
        return new StatementPassword(userId, importJobId, null, value, consentVersion, consentedAt);
    }

    public static StatementPassword forStatement(UUID userId, UUID statementImportId, EncryptedValue value,
                                                 String consentVersion, Instant consentedAt) {
        return new StatementPassword(userId, null, statementImportId, value, consentVersion, consentedAt);
    }

    /** The same ciphertext and consent, owned by another statement: an upload that produced several
     *  statements, or a re-import of this one. The consent given for the file covers each of them. */
    public StatementPassword copyFor(UUID otherStatementImportId) {
        return new StatementPassword(userId, null, otherStatementImportId, credential(), consentVersion, consentedAt);
    }

    /** Replaces the password (a user re-entering it with consent). */
    public void replace(EncryptedValue value, String consentVersion, Instant consentedAt) {
        this.encryptedPassword = value.ciphertext();
        this.encryptionKeyId = value.keyId();
        this.consentVersion = consentVersion;
        this.consentedAt = consentedAt;
    }

    public EncryptedValue credential() {
        return new EncryptedValue(encryptionKeyId, encryptedPassword);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getImportJobId() { return importJobId; }
    public UUID getStatementImportId() { return statementImportId; }
    public String getConsentVersion() { return consentVersion; }
    public Instant getConsentedAt() { return consentedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
