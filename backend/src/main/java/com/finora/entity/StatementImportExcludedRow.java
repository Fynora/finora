package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of a confirmed statement that the user left out on the review screen -- unticked by hand,
 * or left unticked after the import flagged it as a likely duplicate. A statement refresh re-reads
 * the statement and must recognise these rows rather than add them as "new"; see V234 for why this
 * can only be recorded at import time.
 */
@Entity
@Table(name = "statement_import_excluded_rows")
public class StatementImportExcludedRow {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "statement_import_id", nullable = false)
    private UUID statementImportId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "row_position")
    private Integer rowPosition;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "txn_type", nullable = false)
    private String txnType;

    @Column(name = "likely_duplicate", nullable = false)
    private boolean likelyDuplicate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StatementImportExcludedRow() {}

    public StatementImportExcludedRow(UUID statementImportId, UUID userId, Integer rowPosition, LocalDate txnDate,
                                      String description, BigDecimal amount, String txnType, boolean likelyDuplicate) {
        this.statementImportId = statementImportId;
        this.userId = userId;
        this.rowPosition = rowPosition;
        this.txnDate = txnDate;
        this.description = description;
        this.amount = amount;
        this.txnType = txnType;
        this.likelyDuplicate = likelyDuplicate;
    }

    public UUID getId() { return id; }
    public UUID getStatementImportId() { return statementImportId; }
    public UUID getUserId() { return userId; }
    public Integer getRowPosition() { return rowPosition; }
    public LocalDate getTxnDate() { return txnDate; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public String getTxnType() { return txnType; }
    public boolean isLikelyDuplicate() { return likelyDuplicate; }
    public Instant getCreatedAt() { return createdAt; }
}
