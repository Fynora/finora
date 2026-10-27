package com.finora.service;

import com.finora.util.MerchantNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detaches the rows that name nobody from the engine-made merchants V294 queued
 * ({@code merchant_nobody_detach}), and removes a merchant left with nothing on it.
 *
 * <p>Until this change MerchantNormalizationEngine made a merchant for every narration, including a
 * bare UPI or NACH reference and a cash withdrawal; each was shown in Top Merchants and the review
 * groups as if it were a shop. New rows get no merchant now ({@link MerchantNames#narrationNamesNobody}).
 *
 * <p>Per queued merchant that is still TEMPORARY: every row whose narration names nobody gets
 * {@code merchant_id = NULL}. A row in the bin (soft-deleted) is detached too, so it comes back
 * without the merchant if it is restored. The merchant is then deleted only if no row points at it
 * any more <b>and</b> it has no learning -- no learned category, no learning audit, no learning
 * event. Deleting would cascade all three away, as {@code MerchantReviewService}'s own doc warns.
 * Its aliases go with it, so a later import of the same narration meets the rule, not a dead alias.
 * A merchant a person approved is left as it is.
 *
 * <p><b>It never waits on anyone.</b> The merchant and its rows are locked {@code SKIP LOCKED}
 * before anything is read or written. A merchant an import is linking a row to (it holds the
 * merchant, {@link com.finora.repository.MerchantRepository#lockForLink}), or one with a row
 * another transaction is changing, is left queued for the next pass. So an import is never failed or
 * deadlocked by this sweep: it waits at most for one batch, then finds the merchant gone and makes a
 * new one. And once the merchant is held here, no new row can be linked to it until this commits,
 * so what is read is what is deleted.
 *
 * <p>Modes ({@code app.merchant-nobody-detach.sweep.mode}): {@code dry-run}, the default, reads the
 * whole queue once per start, logs what it would do and writes nothing; {@code apply} works through
 * the queue in bounded batches until it is empty; {@code off} does nothing. Each detach is audited
 * ({@code MERCHANT_ROWS_DETACHED}) and each removal ({@code MERCHANT_DISCARDED}), counts only:
 * narrations and names are user financial data and are neither logged nor audited.
 */
@Component
public class MerchantNobodyDetachSweepService {

    private static final Logger log = LoggerFactory.getLogger(MerchantNobodyDetachSweepService.class);
    private static final String ACTOR = "merchant-nobody-detach";

    /** Whether any learning hangs off a merchant: deleting the merchant would cascade it away. */
    private static final String HAS_LEARNING = """
            (EXISTS (SELECT 1 FROM merchant_category_learning l WHERE l.merchant_id = m.id)
             OR EXISTS (SELECT 1 FROM merchant_learning_audit a WHERE a.merchant_id = m.id)
             OR EXISTS (SELECT 1 FROM merchant_learning_events e WHERE e.merchant_id = m.id))
            """;

    @Value("${app.merchant-nobody-detach.sweep.mode:dry-run}")
    private String mode;

    /**
     * Merchants per transaction. Small on purpose: the merchant index covers live rows only, so each
     * merchant costs a few scans of transactions (reading its rows with the binned ones, the delete's
     * re-check, and the foreign key's SET NULL). Measured on 1,000,000 invented rows: 200 merchants
     * held one transaction for about 26 s, so 25 come to about 3 s at the same cost per merchant.
     * That is also the longest an import linking a row to one of them can wait.
     */
    @Value("${app.merchant-nobody-detach.sweep.batch-size:25}")
    private int batchSize;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    private volatile boolean dryRunReported;

    public MerchantNobodyDetachSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, AuditService auditService) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.merchant-nobody-detach.sweep.interval-ms:300000}",
            initialDelayString = "${app.merchant-nobody-detach.sweep.initial-delay-ms:240000}")
    public void scheduledSweep() {
        if ("apply".equals(mode)) {
            Result r = apply();
            if (r.decided() > 0 || r.failed() > 0 || r.busy() > 0) {
                log.info("Merchant nobody detach: {} rows detached, {} merchants removed, {} kept, {} left as they were, "
                                + "{} busy (retried next pass), {} failed.{}",
                        r.rowsDetached(), r.merchantsDeleted(), r.merchantsKept(), r.unchanged(), r.busy(), r.failed(),
                        r.drained() ? " Queue drained." : "");
            }
        } else if ("dry-run".equals(mode) && !dryRunReported) {
            dryRunReported = true;
            Result r = dryRun();
            log.info("Merchant nobody detach DRY RUN (nothing written): {} rows would be detached, {} merchants removed, "
                            + "{} kept, {} left as they were, {} failed. Set app.merchant-nobody-detach.sweep.mode=apply to write.",
                    r.rowsDetached(), r.merchantsDeleted(), r.merchantsKept(), r.unchanged(), r.failed());
        }
    }

    private record Queued(UUID merchantId, UUID userId, String lifecycle) {}

    private record Row(UUID id, String description) {}

    private static final class Tally {
        int rowsDetached, deleted, kept, unchanged, busy, failed;
    }

    /** One batch of up to {@code batchSize} queued merchants, in one transaction; each decided one is dequeued. */
    public Result apply() {
        List<Queued> batch = queued(batchSize);
        if (batch.isEmpty()) return new Result(0, 0, 0, 0, 0, 0, true);
        Tally tally = new Tally();
        transactionTemplate.executeWithoutResult(tx -> {
            for (Queued merchant : batch) {
                if (decide(merchant, tally, true)) {
                    jdbc.update("DELETE FROM merchant_nobody_detach WHERE merchant_id = ?", merchant.merchantId());
                }
            }
        });
        return new Result(tally.rowsDetached, tally.deleted, tally.kept, tally.unchanged, tally.busy, tally.failed,
                batch.size() < batchSize && tally.failed == 0 && tally.busy == 0);
    }

    /** What {@link #apply} would do to the whole queue, writing nothing and locking nothing. */
    public Result dryRun() {
        Tally tally = new Tally();
        for (Queued merchant : queued(Integer.MAX_VALUE)) decide(merchant, tally, false);
        return new Result(tally.rowsDetached, tally.deleted, tally.kept, tally.unchanged, tally.busy, tally.failed, true);
    }

    private List<Queued> queued(int limit) {
        // A LEFT JOIN, so a queued merchant that is gone is still read -- and dropped. (The foreign
        // key's cascade normally removes it first.)
        return jdbc.query("""
                SELECT q.merchant_id, m.user_id, m.lifecycle_status
                FROM merchant_nobody_detach q
                LEFT JOIN merchants m ON m.id = q.merchant_id
                ORDER BY q.merchant_id
                LIMIT ?
                """, (rs, n) -> new Queued(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)), limit);
    }

    /** False when the merchant could not be decided now; it stays queued for the next pass. */
    private boolean decide(Queued merchant, Tally tally, boolean write) {
        if (merchant.userId() == null || !"TEMPORARY".equals(merchant.lifecycle())) {
            tally.unchanged++;
            return true;
        }
        UUID merchantId = merchant.merchantId();
        List<Row> rows;
        if (write) {
            // The merchant first: held, no row can be linked to it until this commits.
            List<UUID> held = jdbc.queryForList(
                    "SELECT id FROM merchants WHERE id = ? AND lifecycle_status = 'TEMPORARY' FOR UPDATE SKIP LOCKED",
                    UUID.class, merchantId);
            if (held.isEmpty()) {
                boolean stillQueuedShape = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM merchants WHERE id = ? AND lifecycle_status = 'TEMPORARY')",
                        Boolean.class, merchantId));
                if (stillQueuedShape) {
                    tally.busy++;   // another transaction holds it -- an import linking a row
                    return false;
                }
                tally.unchanged++;  // gone, or approved since the queue was read
                return true;
            }
            rows = jdbc.query("SELECT id, description FROM transactions WHERE merchant_id = ? FOR UPDATE SKIP LOCKED",
                    (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)), merchantId);
            Long all = jdbc.queryForObject("SELECT count(*) FROM transactions WHERE merchant_id = ?", Long.class, merchantId);
            if (all == null || all != rows.size()) {
                tally.busy++;       // a row another transaction is changing
                return false;
            }
        } else {
            rows = jdbc.query("SELECT id, description FROM transactions WHERE merchant_id = ?",
                    (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)), merchantId);
        }
        // Only the rule is caught: it parses narrations, and one odd narration must not stop the
        // queue. A database error is left to fail the whole batch, as in the other sweeps -- caught
        // here it would leave the transaction aborted for every merchant after it.
        List<UUID> nobody;
        try {
            nobody = rows.stream().filter(r -> MerchantNames.narrationNamesNobody(r.description())).map(Row::id).toList();
        } catch (RuntimeException e) {
            log.error("Merchant nobody detach failed for merchant {}: {}", merchantId, e.toString());
            tally.failed++;
            return false;
        }
        if (nobody.isEmpty()) {
            tally.unchanged++;
            return true;
        }
        if (!write) {
            boolean learned = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT " + HAS_LEARNING + " FROM merchants m WHERE m.id = ?", Boolean.class, merchantId));
            tally.rowsDetached += nobody.size();
            if (nobody.size() == rows.size() && !learned) tally.deleted++; else tally.kept++;
            return true;
        }
        int detached = 0;
        for (UUID id : nobody) {
            detached += jdbc.update("UPDATE transactions SET merchant_id = NULL WHERE id = ? AND merchant_id = ?", id, merchantId);
        }
        tally.rowsDetached += detached;
        if (detached > 0) {
            auditService.record(merchant.userId(), "MERCHANT_ROWS_DETACHED", "Merchant", merchantId,
                    Map.of("rows", detached, "actor", ACTOR));
        }
        int deleted = jdbc.update("""
                DELETE FROM merchants m
                WHERE m.id = ? AND m.lifecycle_status = 'TEMPORARY'
                  AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.merchant_id = m.id)
                  AND NOT\s""" + HAS_LEARNING, merchantId);
        if (deleted > 0) {
            auditService.record(merchant.userId(), "MERCHANT_DISCARDED", "Merchant", merchantId, Map.of("actor", ACTOR));
            tally.deleted++;
        } else {
            tally.kept++;
        }
        return true;
    }

    /**
     * @param rowsDetached     rows unlinked from their merchant (in a dry run: would be)
     * @param merchantsDeleted merchants removed because nothing was left on them
     * @param merchantsKept    merchants that lost rows but keep others, or keep learning
     * @param unchanged        queued merchants left as they were (approved, gone, or no row naming nobody)
     * @param busy             merchants another transaction was holding; left queued for the next pass
     * @param failed           merchants whose narrations the rule threw on; left queued for the next pass
     * @param drained          true when this pass emptied the queue
     */
    public record Result(int rowsDetached, int merchantsDeleted, int merchantsKept, int unchanged, int busy, int failed,
                         boolean drained) {
        int decided() { return merchantsDeleted + merchantsKept + unchanged; }
    }
}
