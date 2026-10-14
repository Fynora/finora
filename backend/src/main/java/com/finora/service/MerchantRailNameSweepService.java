package com.finora.service;

import com.finora.util.MerchantNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Renames the engine-made merchants V268 queued ({@code merchant_rail_name_recheck}) -- stored under
 * a name that opens with a payment rail -- to the name a fresh import of their own narrations gives
 * them now ({@link MerchantNames#importedNameFor}).
 *
 * <p>The new name is what {@link MerchantNormalizationEngine} would store today, so the merchant's
 * grouping key becomes the key its payee's next narration is matched by: a payee whose old name keyed
 * differently stops getting a second merchant, and its learned category, which stays on the merchant
 * id, applies to those rows again.
 *
 * <p>Left as it is, and dropped from the queue:
 * <ul>
 *   <li>a merchant a person has approved since, or renamed so it no longer opens with a rail;</li>
 *   <li>a merchant with no narration to read, or whose narrations give the name it already has;</li>
 *   <li>a merchant whose new name would share its grouping key with another merchant of the same
 *       user. Two merchants with one key compete for the same narrations; merging them would pool
 *       their learned categories, which is a person's decision (the owner's, 2026-10-14).</li>
 * </ul>
 *
 * <p>Three modes ({@code app.merchant-rail-name-recheck.sweep.mode}): {@code dry-run}, the default,
 * reads the whole queue once per start, logs what it would do and writes nothing; {@code apply}
 * renames in bounded batches until the queue is empty; {@code off} does nothing. The UPDATE only
 * writes over the name and status read here, so a rename or approval landing in between wins. Each
 * rename is audited ({@code MERCHANT_NAME_RECHECKED}). Names are not logged; they are user financial
 * data.
 */
@Component
public class MerchantRailNameSweepService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRailNameSweepService.class);

    /** The widest value merchants.canonical_name holds (V7). */
    private static final int MAX_NAME_LENGTH = 255;

    @Value("${app.merchant-rail-name-recheck.sweep.mode:dry-run}")
    private String mode;

    @Value("${app.merchant-rail-name-recheck.sweep.batch-size:200}")
    private int batchSize;

    /** Narrations read per merchant, newest first. The name is a majority, so more add little. */
    @Value("${app.merchant-rail-name-recheck.sweep.narrations-per-merchant:200}")
    private int narrationsPerMerchant;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    private volatile boolean dryRunReported;

    public MerchantRailNameSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, AuditService auditService) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.merchant-rail-name-recheck.sweep.interval-ms:300000}",
            initialDelayString = "${app.merchant-rail-name-recheck.sweep.initial-delay-ms:180000}")
    public void scheduledSweep() {
        if ("apply".equals(mode)) {
            Result result = apply();
            if (result.decided() > 0 || result.failed() > 0) {
                log.info("Merchant name recheck: {} renamed, {} left (would share a key), {} left as they were, {} failed.{}",
                        result.renamed(), result.sharedKey(), result.unchanged(), result.failed(),
                        result.drained() ? " Queue drained." : "");
            }
        } else if ("dry-run".equals(mode) && !dryRunReported) {
            dryRunReported = true;
            Result result = dryRun();
            log.info("Merchant name recheck DRY RUN (nothing written): {} would be renamed, {} left (would share a key), "
                            + "{} left as they were, {} failed. Set app.merchant-rail-name-recheck.sweep.mode=apply to rename.",
                    result.renamed(), result.sharedKey(), result.unchanged(), result.failed());
        }
    }

    private record Queued(UUID merchantId, UUID userId, String name, String lifecycle) {}

    private enum Outcome { RENAMED, SHARED_KEY, UNCHANGED, FAILED }

    /** One pass over up to {@code batchSize} queued merchants: renames, audits and dequeues. */
    public Result apply() {
        List<Queued> batch = queued(batchSize);
        if (batch.isEmpty()) return new Result(0, 0, 0, 0, true);
        int[] counts = new int[Outcome.values().length];
        transactionTemplate.executeWithoutResult(tx -> {
            Map<UUID, Set<String>> takenKeys = new HashMap<>();
            for (Queued merchant : batch) {
                Outcome outcome = decideAndMaybeRename(merchant, takenKeys, true);
                counts[outcome.ordinal()]++;
                if (outcome != Outcome.FAILED) {
                    jdbc.update("DELETE FROM merchant_rail_name_recheck WHERE merchant_id = ?", merchant.merchantId());
                }
            }
        });
        boolean drained = batch.size() < batchSize && counts[Outcome.FAILED.ordinal()] == 0;
        return result(counts, drained);
    }

    /** What {@link #apply} would do to the whole queue, writing nothing. */
    public Result dryRun() {
        List<Queued> all = queued(Integer.MAX_VALUE);
        int[] counts = new int[Outcome.values().length];
        Map<UUID, Set<String>> takenKeys = new HashMap<>();
        for (Queued merchant : all) counts[decideAndMaybeRename(merchant, takenKeys, false).ordinal()]++;
        return result(counts, true);
    }

    private List<Queued> queued(int limit) {
        // A LEFT JOIN, so a queued merchant that is gone is still read -- and dropped. (The foreign
        // key's cascade normally removes it first.)
        return jdbc.query("""
                SELECT q.merchant_id, m.user_id, m.canonical_name, m.lifecycle_status
                FROM merchant_rail_name_recheck q
                LEFT JOIN merchants m ON m.id = q.merchant_id
                ORDER BY q.merchant_id
                LIMIT ?
                """, (rs, n) -> new Queued(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4)), limit);
    }

    /**
     * {@code takenKeys} holds, per user, the keys this pass has already given a renamed merchant: in
     * a dry run nothing is written, so a second merchant heading for the same key would otherwise not
     * see the first.
     */
    private Outcome decideAndMaybeRename(Queued merchant, Map<UUID, Set<String>> takenKeys, boolean write) {
        if (merchant.name() == null || !"TEMPORARY".equals(merchant.lifecycle())) return Outcome.UNCHANGED;
        List<String> narrations = jdbc.queryForList("""
                SELECT description FROM transactions
                WHERE merchant_id = ? AND deleted_at IS NULL AND description IS NOT NULL
                ORDER BY txn_date DESC
                LIMIT ?
                """, String.class, merchant.merchantId(), narrationsPerMerchant);
        List<String> others = jdbc.queryForList("SELECT canonical_name FROM merchants WHERE user_id = ? AND id <> ?",
                String.class, merchant.userId(), merchant.merchantId());

        // Only the naming is caught: it parses narrations, and one odd narration must not stop the
        // queue. A database error is left to fail the whole batch, as in the other sweeps -- caught
        // here it would leave the transaction aborted for every merchant after it.
        String newName;
        String key;
        boolean shared;
        try {
            if (!MerchantNames.opensWithRail(merchant.name())) return Outcome.UNCHANGED;
            String imported = MerchantNames.importedNameFor(narrations);
            if (imported == null) return Outcome.UNCHANGED;
            newName = imported.length() > MAX_NAME_LENGTH ? imported.substring(0, MAX_NAME_LENGTH) : imported;
            if (newName.equals(merchant.name())) return Outcome.UNCHANGED;
            key = MerchantNormalizationEngine.groupingKeyOfName(newName);
            Set<String> taken = takenKeys.computeIfAbsent(merchant.userId(), id -> new HashSet<>());
            shared = key != null && (taken.contains(key)
                    || others.stream().anyMatch(other -> key.equals(MerchantNormalizationEngine.groupingKeyOfName(other))));
        } catch (RuntimeException e) {
            log.error("Merchant name recheck failed for merchant {}: {}", merchant.merchantId(), e.toString());
            return Outcome.FAILED;
        }
        if (shared) return Outcome.SHARED_KEY;
        if (key != null) takenKeys.get(merchant.userId()).add(key);
        if (!write) return Outcome.RENAMED;

        int written = jdbc.update("""
                UPDATE merchants SET canonical_name = ?, updated_at = now()
                WHERE id = ? AND canonical_name = ? AND lifecycle_status = 'TEMPORARY'
                """, newName, merchant.merchantId(), merchant.name());
        if (written == 0) return Outcome.UNCHANGED;
        auditService.record(merchant.userId(), "MERCHANT_NAME_RECHECKED", "Merchant", merchant.merchantId(),
                Map.of("previousName", merchant.name(), "newName", newName, "actor", "merchant-rail-name-recheck"));
        return Outcome.RENAMED;
    }

    private static Result result(int[] counts, boolean drained) {
        return new Result(counts[Outcome.RENAMED.ordinal()], counts[Outcome.SHARED_KEY.ordinal()],
                counts[Outcome.UNCHANGED.ordinal()], counts[Outcome.FAILED.ordinal()], drained);
    }

    /**
     * @param renamed   merchants given the name a fresh import gives them (in a dry run: would be)
     * @param sharedKey merchants left as they are because the new name would share a grouping key
     *                  with another merchant of the same user
     * @param unchanged merchants left as they are for any other reason
     * @param failed    merchants that threw; left queued for the next pass
     * @param drained   true when this pass emptied the queue
     */
    public record Result(int renamed, int sharedKey, int unchanged, int failed, boolean drained) {
        int decided() { return renamed + sharedKey + unchanged; }
    }
}
