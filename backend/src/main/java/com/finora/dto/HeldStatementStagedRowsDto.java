package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The rows a held statement staged, as a reviewer reads them beside the findings.
 *
 * <p>Statement content, unlike {@link HeldStatementDto}: served only by the gated, audited
 * {@code /staged-rows} endpoint, the same gate as the document itself. It is the whole review for a
 * statement whose file cannot be opened ({@link HeldStatementDto#lockedWithoutPassword()}), and the
 * thing approving releases for every other one.
 *
 * @param sections one per account section, in the order the statement printed them; a CSV or a
 *                 single-account PDF has exactly one
 */
public record HeldStatementStagedRowsDto(String heldId, List<HeldSection> sections) {

    /**
     * @param accountName the parser's bank or generic account name, never a raw filename
     * @param accountNumberMasked only when the statement printed one
     * @param totalParsed rows the parser read for this section, staged or not
     */
    public record HeldSection(String accountName, String accountNumberMasked, String accountType,
                               LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
                               BigDecimal openingBalance, BigDecimal closingBalance,
                               int totalParsed, List<HeldRow> rows) {}

    /** {@code type} is DEBIT or CREDIT; {@code balanceAfter} and {@code referenceNumber} only when
     *  the statement had those columns. */
    public record HeldRow(LocalDate date, String description, BigDecimal amount, String type,
                           BigDecimal balanceAfter, String referenceNumber) {}
}
