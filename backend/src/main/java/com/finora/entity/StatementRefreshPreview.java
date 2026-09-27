package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** What a statement refresh would change for one statement under one build. See V235. */
@Entity
@Table(name = "statement_refresh_previews")
public class StatementRefreshPreview {

    /** CHANGES: a refresh would change something. NEEDS_REVIEW: it would remove so much of the
     *  statement that it looks like a parser regression, so it is held for an admin and never offered
     *  to the user. NEEDS_PASSWORD: a locked PDF with no saved password. FAILED: the stored file
     *  could not be read or parsed (or parsed to no rows at all); the reason is in detail. */
    public enum Status { CHANGES, NO_CHANGES, NEEDS_REVIEW, NEEDS_PASSWORD, FAILED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "statement_import_id", nullable = false)
    private UUID statementImportId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "parser_version", nullable = false, length = 40)
    private String parserVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "rows_changed", nullable = false) private int rowsChanged;
    @Column(name = "rows_added", nullable = false) private int rowsAdded;
    @Column(name = "rows_removed", nullable = false) private int rowsRemoved;
    @Column(name = "rows_conflicting", nullable = false) private int rowsConflicting;
    @Column(name = "rows_unchanged", nullable = false) private int rowsUnchanged;
    @Column(name = "facts_changed", nullable = false) private int factsChanged;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    protected StatementRefreshPreview() {}

    public StatementRefreshPreview(UUID statementImportId, UUID userId, String parserVersion, Status status) {
        this.statementImportId = statementImportId;
        this.userId = userId;
        this.parserVersion = parserVersion;
        this.status = status;
    }

    public void setCounts(int changed, int added, int removed, int conflicting, int unchanged, int factsChanged) {
        this.rowsChanged = changed;
        this.rowsAdded = added;
        this.rowsRemoved = removed;
        this.rowsConflicting = conflicting;
        this.rowsUnchanged = unchanged;
        this.factsChanged = factsChanged;
    }

    public void setDetail(Map<String, Object> detail) { this.detail = detail; }

    public UUID getId() { return id; }
    public UUID getStatementImportId() { return statementImportId; }
    public UUID getUserId() { return userId; }
    public String getParserVersion() { return parserVersion; }
    public Status getStatus() { return status; }
    public int getRowsChanged() { return rowsChanged; }
    public int getRowsAdded() { return rowsAdded; }
    public int getRowsRemoved() { return rowsRemoved; }
    public int getRowsConflicting() { return rowsConflicting; }
    public int getRowsUnchanged() { return rowsUnchanged; }
    public int getFactsChanged() { return factsChanged; }
    public Map<String, Object> getDetail() { return detail; }
    public Instant getComputedAt() { return computedAt; }
}
