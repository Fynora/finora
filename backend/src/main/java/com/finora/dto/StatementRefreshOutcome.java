package com.finora.dto;

import com.finora.entity.StatementRefreshRun;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What refreshing one statement did. {@code status} NEEDS_PASSWORD means nothing was attempted yet:
 * the statement is a protected PDF and the request carried no (or a wrong) password.
 * {@code reason} is set when nothing was applied; {@code runId} whenever the refresh was recorded.
 */
public record StatementRefreshOutcome(UUID statementId, StatementRefreshRun.Status status, int rowsChanged,
                                      int rowsAdded, int rowsRemoved, int factsChanged, BigDecimal balanceChange,
                                      String reason, UUID runId) {

    public static StatementRefreshOutcome of(StatementRefreshRun run) {
        Object reason = run.getDetail() == null ? null : run.getDetail().get("reason");
        return new StatementRefreshOutcome(run.getStatementImportId(), run.getStatus(), run.getRowsChanged(),
                run.getRowsAdded(), run.getRowsRemoved(), run.getFactsChanged(), run.getBalanceChange(),
                reason == null ? null : reason.toString(), run.getId());
    }
}
