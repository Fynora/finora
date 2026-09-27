package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One refresh of one statement and what it changed. See V238. */
@Entity
@Table(name = "statement_refresh_runs")
public class StatementRefreshRun {

    public enum Status { APPLIED, NO_CHANGES, NEEDS_PASSWORD, NEEDS_REVIEW, FAILED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "statement_import_id", nullable = false)
    private UUID statementImportId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "parser_version", length = 40)
    private String parserVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "rows_changed", nullable = false) private int rowsChanged;
    @Column(name = "rows_added", nullable = false) private int rowsAdded;
    @Column(name = "rows_removed", nullable = false) private int rowsRemoved;
    @Column(name = "facts_changed", nullable = false) private int factsChanged;

    @Column(name = "balance_change", precision = 14, scale = 2)
    private BigDecimal balanceChange;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected StatementRefreshRun() {}

    public StatementRefreshRun(UUID statementImportId, UUID userId, String parserVersion, Status status) {
        this.statementImportId = statementImportId;
        this.userId = userId;
        this.parserVersion = parserVersion;
        this.status = status;
    }

    public void setCounts(int changed, int added, int removed, int factsChanged) {
        this.rowsChanged = changed;
        this.rowsAdded = added;
        this.rowsRemoved = removed;
        this.factsChanged = factsChanged;
    }

    public void setBalanceChange(BigDecimal balanceChange) { this.balanceChange = balanceChange; }
    public void setDetail(Map<String, Object> detail) { this.detail = detail; }

    public UUID getId() { return id; }
    public UUID getStatementImportId() { return statementImportId; }
    public UUID getUserId() { return userId; }
    public String getParserVersion() { return parserVersion; }
    public Status getStatus() { return status; }
    public int getRowsChanged() { return rowsChanged; }
    public int getRowsAdded() { return rowsAdded; }
    public int getRowsRemoved() { return rowsRemoved; }
    public int getFactsChanged() { return factsChanged; }
    public BigDecimal getBalanceChange() { return balanceChange; }
    public Map<String, Object> getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
