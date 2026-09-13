package com.finora.integrations.setu;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction as Setu's FI-data response reports it for a DEPOSIT account. Field shape follows
 * the public ReBIT/Sahamati AA FI-data JSON schema -- NOT verified against a real Setu sandbox
 * response (see docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md, Task 2;
 * the same "flag, don't guess" treatment the link-lifecycle plan already gave its own unverified
 * webhook-signature scheme). AccountAggregatorTransactionMapper is the only reader of {@code type}'s
 * raw string value.
 */
public record SetuFiDataTransaction(String txnId, String type, BigDecimal amount, LocalDate valueDate,
                                     LocalDate transactionDate, String narration,
                                     BigDecimal currentBalance, String reference) {}
