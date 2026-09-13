package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Maps Setu's FI-data transaction list into new Transaction rows for one account, filtering out
 * anything already seen. See the design spec's "Transaction identity and idempotency" section --
 * externalTxnId is checked first (a hint, not a guarantee: not established whether every FIP
 * populates it, or whether it survives a pending->posted transition), falling back to a fingerprint
 * that is always computed and always checked.
 *
 * <p>Insert-only, per Plan 2's scope: a row that already exists (by either key) is silently
 * skipped, not updated. A changed or vanished upstream transaction is Plan 6's concern, not this
 * class's.
 */
@Component
public class AccountAggregatorTransactionMapper {

    private final TransactionRepository transactionRepository;

    public AccountAggregatorTransactionMapper(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<Transaction> mapNew(UUID userId, UUID accountId, List<SetuFiDataTransaction> fetched) {
        List<Transaction> result = new ArrayList<>();
        for (SetuFiDataTransaction source : fetched) {
            String fingerprint = fingerprint(accountId, source);

            if (source.txnId() != null
                    && transactionRepository.existsByAccountIdAndExternalTxnId(accountId, source.txnId())) {
                continue;
            }
            if (transactionRepository.existsByAccountIdAndTransactionFingerprint(accountId, fingerprint)) {
                continue;
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
            result.add(txn);
        }
        return result;
    }

    /** hash(accountId, amount, direction, valueDate, normalize(narration)) -- see the design spec.
     *  Deliberately excludes txnId and reference: both are the least reliable fields across FIPs
     *  (per the same spec section), and including an unreliable field in the fallback that exists
     *  specifically to cover for that field's unreliability would defeat the point. */
    private static String fingerprint(UUID accountId, SetuFiDataTransaction source) {
        String normalizedNarration = source.narration() == null ? "" :
                source.narration().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String raw = String.join("|",
                accountId.toString(),
                source.amount().stripTrailingZeros().toPlainString(),
                source.type() == null ? "" : source.type().toUpperCase(Locale.ROOT),
                String.valueOf(source.valueDate()),
                normalizedNarration);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every supported JVM", e);
        }
    }
}
