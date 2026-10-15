package com.finora.dto;

import java.time.LocalDate;

/**
 * Says that a month's figures are "so far": at least one statement-imported account was active
 * around that month but its latest statement ends before the month does, so the month is missing
 * that account's last days (or all of it).
 *
 * <p>One top-level record, not a nested one per response: OpenAPI schemas are keyed by simple name,
 * so a nested copy in each DTO would collide. Reused by the Dashboard's month and range summaries.
 *
 * @param accountName  the account whose statement reaches the least far
 * @param statementEnd the last day that account's statements cover
 * @param accountCount how many accounts are behind, this one included -- a client can say "and N
 *                     more" without listing them
 */
public record StatementBehindDto(String accountName, LocalDate statementEnd, int accountCount) {}
