package com.finora.dto;

import java.time.LocalDate;

/**
 * Says that a month's figures are "so far": at least one account was active around that month but
 * its data stops before the month does -- a statement-imported account whose latest statement ends
 * early, or a linked account that is not syncing -- so the month is missing that account's last
 * days (or all of it).
 *
 * <p>One top-level record, not a nested one per response: OpenAPI schemas are keyed by simple name,
 * so a nested copy in each DTO would collide. Reused by the Dashboard's month and range summaries.
 *
 * @param accountName   the account whose data reaches the least far
 * @param coveredThrough the last day that account's data is known to cover: its latest statement's
 *                      end, or, for a linked account that is not delivering, its last successful
 *                      sync (or newest transaction when the last attempt failed)
 * @param accountCount  how many accounts are behind, this one included -- a client can say "and N
 *                      more" without listing them
 */
public record StatementBehindDto(String accountName, LocalDate coveredThrough, int accountCount) {}
