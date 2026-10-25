package com.finora.imports.send;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One code a user asked for on their phone to send a statement from a computer (Gate 2b spec §7,
 * V293). Read-only here: {@link StatementSendService} writes the rows with single SQL statements, so
 * claiming a code is one atomic UPDATE rather than a read-then-save.
 */
@Entity
@Table(name = "statement_send_codes")
public class StatementSendCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "import_job_id")
    private UUID importJobId;

    protected StatementSendCode() {
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getImportJobId() { return importJobId; }
}
