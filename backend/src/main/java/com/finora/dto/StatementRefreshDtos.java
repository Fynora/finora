package com.finora.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Statement refresh, step 5: what the app shows -- the statements an improved parser would change,
 * and afterwards what refreshing them changed. Names are prefixed: springdoc keys schemas by simple
 * class name, and "Summary"/"Row" are taken many times over.
 */
public final class StatementRefreshDtos {

    private StatementRefreshDtos() {}

    /**
     * One statement the latest check found something to do for. {@code status} is CHANGES (a
     * refresh would change it) or NEEDS_PASSWORD (a protected PDF with no saved password, so it
     * could not be checked). {@code rowsRemoved} includes rows that no longer pair up.
     */
    public record RefreshPendingStatement(UUID statementImportId, String status, String fileName, String accountName,
                                          LocalDate periodStart, LocalDate periodEnd, int rowsChanged, int rowsAdded,
                                          int rowsRemoved, int factsChanged, boolean passwordSaved) {}

    /**
     * The banner's data. {@code enabled} false means refreshing is switched off on this deployment
     * and the client shows nothing. {@code savePasswordAvailable} says whether the password prompt
     * may offer to keep the password.
     */
    public record RefreshOverview(boolean enabled, boolean savePasswordAvailable,
                                  List<RefreshPendingStatement> updatable,
                                  List<RefreshPendingStatement> needsPassword) {}

    public record RefreshRowView(UUID transactionId, String date, String description, String amount, String type) {}

    public record RefreshFieldChange(String field, String before, String after) {}

    public record RefreshChangedRow(UUID transactionId, String date, String description, String amount, String type,
                                    List<RefreshFieldChange> changes) {}

    /** {@code userEdited}: the user had edited, categorised, noted or tagged this row. */
    public record RefreshRemovedRow(UUID transactionId, String date, String description, String amount, String type,
                                    boolean userEdited) {}

    /**
     * What one refresh of one statement did. {@code status}: APPLIED, NO_CHANGES, NEEDS_REVIEW (the
     * re-read would remove too much, so nothing was applied), FAILED (nothing applied; see
     * {@code reason}), or NEEDS_PASSWORD (not attempted: no password; {@code runId} is null).
     */
    public record RefreshRunDetail(UUID runId, UUID statementImportId, String fileName, String accountName,
                                   LocalDate periodStart, LocalDate periodEnd, String status, Instant createdAt,
                                   int rowsChanged, int rowsAdded, int rowsRemoved, int factsChanged,
                                   BigDecimal balanceChange, String reason,
                                   List<RefreshChangedRow> changed, List<RefreshRowView> added,
                                   List<RefreshRemovedRow> removed, List<RefreshRowView> skippedAsDuplicate,
                                   List<RefreshFieldChange> facts) {}

    /** {@code remaining}: statements still to update after this call -- the client calls again. */
    public record RefreshAllResult(List<RefreshRunDetail> results, int remaining) {}
}
