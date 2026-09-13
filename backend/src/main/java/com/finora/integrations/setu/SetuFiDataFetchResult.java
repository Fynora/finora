package com.finora.integrations.setu;

import java.math.BigDecimal;
import java.util.List;

/** One fetch's worth of data for a single linked account: enough of the account summary to sanity-
 *  check against the Account this link is attached to, plus the transaction list itself. */
public record SetuFiDataFetchResult(String maskedAccountNumber, BigDecimal currentBalance,
                                     List<SetuFiDataTransaction> transactions) {}
