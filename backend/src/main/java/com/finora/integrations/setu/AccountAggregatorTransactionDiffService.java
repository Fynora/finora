package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The three-way diff -- {new, changed, missing} -- that replaces AccountAggregatorTransactionMapper
 * .mapNew's pure insert-only behavior for the sliding-window re-fetch path (Plan 6, Track B). See
 * that plan's own "Global Constraints": a changed or missing row NEVER has its own amount/
 * narration/etc. mutated -- only pendingBankCorrection is set and an AuditLog row written. Round 3's
 * decision (scope doc, 2026-09-13) is "preserve, don't overwrite," and this class is the one place
 * that guarantee has to actually hold.
 *
 * <p>The identity ceiling from the design spec applies throughout: a row can only be detected as
 * "changed" or "missing" if it has a non-null externalTxnId. transactionFingerprint itself changes
 * the moment amount or narration does (see {@link AccountAggregatorTransactionMapper#fingerprint}'s
 * own doc comment), so it cannot serve as the "same real-world transaction" signal a correction
 * needs -- a row with no reliable txnId degrades to "looks like a new or already-seen row," exactly
 * today's behavior, not a regression (round 3's "design around the assumption, flag the risk").
 */
@Component
public class AccountAggregatorTransactionDiffService {

    private final TransactionRepository transactionRepository;
    private final AuditService auditService;

    public AccountAggregatorTransactionDiffService(TransactionRepository transactionRepository,
                                                     AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
    }

    public record DiffResult(List<Transaction> newTransactions, int changed, int missing) {}

    public DiffResult diff(UUID userId, UUID accountId, LocalDate from, LocalDate to,
                            List<SetuFiDataTransaction> fetched) {
        List<Transaction> existing = transactionRepository.findByAccountIdAndSourceAndTxnDateBetween(
                accountId, Transaction.Source.ACCOUNT_AGGREGATOR, from, to);
        Map<String, Transaction> existingByTxnId = new HashMap<>();
        for (Transaction t : existing) {
            if (t.getExternalTxnId() != null) {
                existingByTxnId.put(t.getExternalTxnId(), t);
            }
        }

        List<Transaction> newTransactions = new ArrayList<>();
        Set<String> seenTxnIds = new HashSet<>();
        int changed = 0;

        for (SetuFiDataTransaction source : fetched) {
            if (source.txnId() != null) {
                seenTxnIds.add(source.txnId());
            }
            String fingerprint = AccountAggregatorTransactionMapper.fingerprint(accountId, source);

            Transaction matched = source.txnId() != null ? existingByTxnId.get(source.txnId()) : null;
            if (matched != null) {
                if (!fingerprint.equals(matched.getTransactionFingerprint())) {
                    matched.setPendingBankCorrection(true);
                    transactionRepository.save(matched);
                    auditService.record(userId, "ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED", "Transaction",
                            matched.getId(), Map.of(
                                    "previousAmount", matched.getAmount(),
                                    "newAmount", source.amount(),
                                    "previousNarration", matched.getDescription() == null ? "" : matched.getDescription(),
                                    "newNarration", source.narration() == null ? "" : source.narration()));
                    changed++;
                }
                // Identical values -- already seen, nothing to do. Matches mapNew's old skip.
                continue;
            }

            if (source.txnId() != null
                    && transactionRepository.existsByAccountIdAndExternalTxnId(accountId, source.txnId())) {
                continue; // Seen outside this window (e.g. re-fetch overlap) -- not this window's concern.
            }
            if (transactionRepository.existsByAccountIdAndTransactionFingerprint(accountId, fingerprint)) {
                continue; // No reliable txnId to detect a correction against -- the identity ceiling.
            }

            Transaction txn = new Transaction();
            txn.setUserId(userId);
            txn.setAccountId(accountId);
            txn.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
            txn.setExternalTxnId(source.txnId());
            txn.setTransactionFingerprint(fingerprint);
            txn.setTxnDate(source.transactionDate() != null ? source.transactionDate() : source.valueDate());
            txn.setAmount(source.amount());
            txn.setTxnType("CREDIT".equalsIgnoreCase(source.type())
                    ? Transaction.Type.INCOME : Transaction.Type.EXPENSE);
            txn.setDescription(source.narration());
            txn.setBalanceAfter(source.currentBalance());
            txn.setReferenceNumber(source.reference());
            newTransactions.add(txn);
        }

        int missing = 0;
        for (Transaction t : existing) {
            if (t.getExternalTxnId() == null) {
                continue; // No stable identity -- never flagged as missing, see the identity ceiling above.
            }
            if (!seenTxnIds.contains(t.getExternalTxnId()) && !t.isPendingBankCorrection()) {
                t.setPendingBankCorrection(true);
                transactionRepository.save(t);
                auditService.record(userId, "ACCOUNT_AGGREGATOR_TRANSACTION_MISSING", "Transaction",
                        t.getId(), Map.of(
                                "amount", t.getAmount(),
                                "narration", t.getDescription() == null ? "" : t.getDescription(),
                                "txnDate", t.getTxnDate().toString()));
                missing++;
            }
        }

        return new DiffResult(newTransactions, changed, missing);
    }
}
