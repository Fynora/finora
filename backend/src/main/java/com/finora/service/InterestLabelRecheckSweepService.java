package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.util.CategoryRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Gives the rows V254 labelled "interest" the label a fresh import gives them now, from the
 * snapshot V255 took ({@code interest_label_recheck}), in bounded batches until it is empty.
 *
 * <p>The decision is {@link CategoryRules#extractMerchantLabel(String, Transaction.Type)} itself,
 * the method every import uses. V254 wrote "interest" on every stored interest credit; the rule now
 * keeps the payer's name when the narration names one, and does not count a refund or reversal of
 * interest as interest earned. A queued row the rule still labels {@link CategoryRules#INTEREST_LABEL}
 * is left as it is.
 *
 * <p>Never a label the user chose: MERCHANT in user_edited_fields is skipped, and the UPDATE only
 * writes over the "interest" V254 wrote, so an edit landing between the read and the write wins.
 *
 * <p>The version moves, so the change stamp (ChangeStampService) sees the row and the app refetches
 * it. Reconciliation is not re-run: the refund pass reads interest from the narration, not the
 * label, and nothing else it decides reads the label of money in.
 *
 * <p>Same shape as {@link CounterpartyBackfillSweepService}: no job table beyond the snapshot, every
 * decided row leaves the queue in the same transaction as its write, and a row that throws is logged
 * and left queued (its narration is not logged; it is user financial data).
 */
@Component
public class InterestLabelRecheckSweepService {

    private static final Logger log = LoggerFactory.getLogger(InterestLabelRecheckSweepService.class);

    @Value("${app.interest-label-recheck.sweep.enabled:true}")
    private boolean sweepEnabled;

    /** Per row: a regex pass and at most one single-row UPDATE, as in the counterparty backfill. */
    @Value("${app.interest-label-recheck.sweep.batch-size:500}")
    private int batchSize;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public InterestLabelRecheckSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /** Flag-gated and {@code fixedDelay} for the reasons {@link CounterpartyBackfillSweepService#scheduledSweep} gives. */
    @Scheduled(fixedDelayString = "${app.interest-label-recheck.sweep.interval-ms:300000}",
            initialDelayString = "${app.interest-label-recheck.sweep.initial-delay-ms:150000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.relabelled() > 0 || result.left() > 0 || result.failed() > 0) {
            log.info("Interest label recheck: {} row(s) relabelled, {} left as they were, {} failed.{}",
                    result.relabelled(), result.left(), result.failed(), result.drained() ? " Backlog drained." : "");
        }
    }

    private record Queued(UUID id, String description, String txnType, String merchant, List<String> userEdited) {}

    /** One pass over up to {@code batchSize} queued rows. */
    public Result sweep() {
        // A LEFT JOIN, so a queued row whose transaction is gone is still read -- and dropped. (The
        // foreign key's cascade normally removes it first.)
        List<Queued> batch = jdbc.query("""
                SELECT q.transaction_id, t.description, t.txn_type, t.merchant, t.user_edited_fields
                FROM interest_label_recheck q
                LEFT JOIN transactions t ON t.id = q.transaction_id
                ORDER BY q.transaction_id
                LIMIT ?
                """, (rs, n) -> new Queued(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), names(rs.getArray(5))), batchSize);
        if (batch.isEmpty()) return new Result(0, 0, 0, true);

        int[] counts = new int[3]; // relabelled, left, failed
        transactionTemplate.executeWithoutResult(tx -> {
            for (Queued row : batch) {
                String label;
                try {
                    label = importLabelIfDifferent(row);
                } catch (RuntimeException e) {
                    counts[2]++;
                    log.error("Interest label recheck failed for transaction {}: {}", row.id(), e.toString());
                    continue;
                }
                int written = label == null ? 0 : jdbc.update("""
                        UPDATE transactions
                        SET merchant = ?, version = version + 1, updated_at = now()
                        WHERE id = ?
                          AND merchant = ?
                          AND NOT ('MERCHANT' = ANY (user_edited_fields))
                        """, label.isEmpty() ? null : label, row.id(), CategoryRules.INTEREST_LABEL);
                if (written > 0) counts[0]++; else counts[1]++;
                jdbc.update("DELETE FROM interest_label_recheck WHERE transaction_id = ?", row.id());
            }
        });
        boolean drained = batch.size() < batchSize && counts[2] == 0;
        return new Result(counts[0], counts[1], counts[2], drained);
    }

    /**
     * The label a fresh import gives the row when it is not "interest", with "" standing for no label
     * at all (the import leaves Transaction.merchant unset when nothing names anyone); null when the
     * row is to be left as it is.
     */
    private static String importLabelIfDifferent(Queued row) {
        if (row.description() == null || row.txnType() == null) return null; // the transaction is gone
        if (!CategoryRules.INTEREST_LABEL.equals(row.merchant())) return null;
        if (row.userEdited().contains(Transaction.EditableField.MERCHANT.name())) return null;
        String label = CategoryRules.extractMerchantLabel(row.description(), Transaction.Type.valueOf(row.txnType()));
        if (CategoryRules.INTEREST_LABEL.equals(label)) return null;
        return label == null ? "" : label;
    }

    private static List<String> names(Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.asList((String[]) array.getArray());
    }

    /**
     * @param relabelled rows given back the label a fresh import gives them
     * @param left       rows decided and left as they were (still "interest", the user's, or gone)
     * @param failed     rows the rule threw on; left queued for the next pass
     * @param drained    whether the queue is now empty
     */
    public record Result(int relabelled, int left, int failed, boolean drained) {
    }
}
