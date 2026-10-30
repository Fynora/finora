package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.util.CategoryRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cleans what older imports saved into a transaction's narration and short name, so a row imported
 * before a parser fix reads the way a fresh import of the same statement reads now.
 *
 * <p>Two repairs, each only where the import left the mark:
 * <ul>
 *   <li><b>The page's print-time stamp.</b> Punjab National Bank prints "Date: &lt;dd/mm/yyyy&gt;
 *       &lt;hh:mm:ss&gt;" at the foot of every page. Until the parser learnt that footer
 *       ({@code PdfTableLocator.PRINT_TIME_FOOTER}) it was appended to the last transaction of each
 *       page. It is removed from the END of the narration only, where the import put it; a narration
 *       that is nothing but the stamp is left alone.</li>
 *   <li><b>A broken short name</b> ({@code transactions.merchant}): none at all, a word carrying a
 *       run of four or more digits (a reference or account number), or a leading rail word
 *       ("in", "upi", "neft", ...). It is replaced with the name {@link CategoryRules#extractMerchantLabel}
 *       reads from the (cleaned) narration today -- the name a fresh import stores -- only when that
 *       name looks clean and differs. Otherwise the row keeps its own.</li>
 * </ul>
 * A field a person edited ({@code DESCRIPTION} or {@code MERCHANT} in {@code user_edited_fields}) is
 * never touched.
 *
 * <p>Three modes ({@code app.transaction-narration-cleanup.sweep.mode}), as
 * {@link MerchantRailNameSweepService}: {@code dry-run}, the default, reads every candidate once per
 * start, logs how many rows would change and writes nothing; {@code apply} cleans in bounded batches
 * until one whole pass is done, then rests until the next start (new imports are already clean, and a
 * deploy that teaches the rules more starts a fresh pass); {@code off} does nothing. The UPDATE only
 * writes over the narration and name read here, so an edit landing in between wins, and it bumps
 * {@code version} so the mobile app's change stamp moves and the row is fetched again. One audit
 * record per user per batch carries the counts. Narrations and names are never logged: they are user
 * financial data.
 */
@Service
public class TransactionNarrationCleanupSweepService {

    private static final Logger log = LoggerFactory.getLogger(TransactionNarrationCleanupSweepService.class);

    /** The widest value transactions.merchant holds (V1). */
    static final int MAX_MERCHANT_LENGTH = 255;

    /** Words that open a broken short name: what is left of the rail when it was read as a name. */
    static final Set<String> RAIL_LEFTOVERS = Set.of("in", "out", "upi", "neft", "imps", "rtgs", "ib", "mob", "ach", "nach", "dr", "cr");

    private static final Pattern REFERENCE_RUN = Pattern.compile("\\d{4,}");

    /** The print-time stamp at the very end of a narration, with the space that joined it on. */
    static final Pattern TRAILING_PRINT_TIME_STAMP =
            Pattern.compile("\\s*Date\\s*:\\s*\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*$");

    /** Both tests in SQL, so only candidates are read. Kept beside {@link #planFor}. */
    private static final String CANDIDATE = """
            ((t.description ~ 'Date\\s*:\\s*\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*$'
              AND NOT ('DESCRIPTION' = ANY(t.user_edited_fields)))
             OR (NOT ('MERCHANT' = ANY(t.user_edited_fields))
                 AND (t.merchant IS NULL
                      OR t.merchant ~ '[0-9]{4,}'
                      OR lower(split_part(t.merchant, ' ', 1)) IN ('in','out','upi','neft','imps','rtgs','ib','mob','ach','nach','dr','cr'))))
            """;

    @Value("${app.transaction-narration-cleanup.sweep.mode:dry-run}")
    private String mode;

    @Value("${app.transaction-narration-cleanup.sweep.batch-size:500}")
    private int batchSize;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    private volatile boolean dryRunReported;
    private volatile boolean passComplete;
    /** Where the next {@code apply} batch starts; a restart begins a new pass from the first id. */
    private volatile UUID cursor;

    public TransactionNarrationCleanupSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                                                   AuditService auditService) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.transaction-narration-cleanup.sweep.interval-ms:300000}",
            initialDelayString = "${app.transaction-narration-cleanup.sweep.initial-delay-ms:240000}")
    public void scheduledSweep() {
        if ("apply".equals(mode)) {
            if (passComplete) return;
            Result result = apply();
            if (result.drained()) passComplete = true;
            if (result.changed() > 0 || result.drained()) {
                log.info("Transaction narration cleanup: {} rows changed ({} stamps removed, {} short names re-read), "
                                + "{} left as they were, of {} read.{}",
                        result.changed(), result.stampsRemoved(), result.namesReread(), result.read() - result.changed(),
                        result.read(), result.drained() ? " Pass complete." : "");
            }
        } else if ("dry-run".equals(mode) && !dryRunReported) {
            dryRunReported = true;
            Result result = dryRun();
            log.info("Transaction narration cleanup DRY RUN (nothing written): {} rows would change ({} stamps removed, "
                            + "{} short names re-read), {} left as they were, of {} read. "
                            + "Set app.transaction-narration-cleanup.sweep.mode=apply to clean them.",
                    result.changed(), result.stampsRemoved(), result.namesReread(), result.read() - result.changed(),
                    result.read());
        }
    }

    public record Result(int read, int changed, int stampsRemoved, int namesReread, boolean drained) {}

    /** What one row becomes: its narration and short name after cleaning, either possibly unchanged. */
    public record Plan(String description, String merchant) {}

    private record Candidate(UUID id, UUID userId, String description, String type, String merchant,
                             List<String> editedFields) {}

    /** One batch from the cursor: cleans what it can, and moves the cursor past what it read. */
    public Result apply() {
        List<Candidate> batch = candidates(cursor, batchSize);
        if (batch.isEmpty()) {
            cursor = null;
            return new Result(0, 0, 0, 0, true);
        }
        int[] counts = new int[3];
        transactionTemplate.executeWithoutResult(tx -> {
            Map<UUID, int[]> perUser = new HashMap<>();
            for (Candidate c : batch) {
                Plan plan = planOf(c);
                boolean stamp = !Objects.equals(plan.description(), c.description());
                boolean name = !Objects.equals(plan.merchant(), c.merchant());
                if (!stamp && !name) continue;
                int written = jdbc.update("""
                        UPDATE transactions SET description = ?, merchant = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND deleted_at IS NULL
                          AND description IS NOT DISTINCT FROM ? AND merchant IS NOT DISTINCT FROM ?
                          AND (? = false OR NOT ('DESCRIPTION' = ANY(user_edited_fields)))
                          AND (? = false OR NOT ('MERCHANT' = ANY(user_edited_fields)))
                        """, plan.description(), plan.merchant(), c.id(), c.description(), c.merchant(), stamp, name);
                if (written > 0) {
                    counts[0]++;
                    if (stamp) counts[1]++;
                    if (name) counts[2]++;
                    int[] u = perUser.computeIfAbsent(c.userId(), k -> new int[2]);
                    if (stamp) u[0]++;
                    if (name) u[1]++;
                }
            }
            perUser.forEach((userId, u) -> auditService.record(userId, "TRANSACTION_NARRATIONS_CLEANED", "Transaction", null,
                    Map.of("stampsRemoved", u[0], "namesReread", u[1], "actor", "transaction-narration-cleanup")));
        });
        boolean drained = batch.size() < batchSize;
        cursor = drained ? null : batch.get(batch.size() - 1).id();
        return new Result(batch.size(), counts[0], counts[1], counts[2], drained);
    }

    /** What {@link #apply} would do to every candidate, writing nothing. */
    public Result dryRun() {
        int read = 0, changed = 0, stamps = 0, names = 0;
        UUID from = null;
        while (true) {
            List<Candidate> batch = candidates(from, batchSize);
            for (Candidate c : batch) {
                Plan plan = planOf(c);
                boolean stamp = !Objects.equals(plan.description(), c.description());
                boolean name = !Objects.equals(plan.merchant(), c.merchant());
                if (stamp || name) changed++;
                if (stamp) stamps++;
                if (name) names++;
            }
            read += batch.size();
            if (batch.size() < batchSize) break;
            from = batch.get(batch.size() - 1).id();
        }
        return new Result(read, changed, stamps, names, true);
    }

    private List<Candidate> candidates(UUID after, int limit) {
        return jdbc.query("SELECT t.id, t.user_id, t.description, t.txn_type, t.merchant, t.user_edited_fields "
                        + "FROM transactions t WHERE t.deleted_at IS NULL AND t.description IS NOT NULL AND " + CANDIDATE
                        + (after == null ? "" : " AND t.id > ?") + " ORDER BY t.id LIMIT ?",
                (rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), editedFields(rs.getArray(6))),
                after == null ? new Object[] {limit} : new Object[] {after, limit});
    }

    private static List<String> editedFields(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(String::valueOf).toList();
    }

    private static Plan planOf(Candidate c) {
        try {
            return planFor(c.description(), c.type(), c.merchant(), c.editedFields());
        } catch (RuntimeException e) {
            log.warn("Transaction narration cleanup could not read row {}: {}", c.id(), e.toString());
            return new Plan(c.description(), c.merchant());
        }
    }

    /**
     * The row after cleaning. The narration loses a trailing print-time stamp unless a person edited
     * it or nothing else would be left. The short name, unless a person edited it, is re-read from the
     * cleaned narration when the saved one looks broken, and replaced only by a clean, different name
     * that fits the column.
     */
    public static Plan planFor(String description, String txnType, String merchant, List<String> editedFields) {
        String cleaned = description;
        if (description != null && !editedFields.contains(Transaction.EditableField.DESCRIPTION.name())) {
            String stripped = TRAILING_PRINT_TIME_STAMP.matcher(description).replaceFirst("");
            // Nothing but the stamp: there is no narration to clean or name to read from it ("date").
            if (stripped.isBlank()) return new Plan(description, merchant);
            cleaned = stripped;
        }
        String name = merchant;
        if (cleaned != null && !editedFields.contains(Transaction.EditableField.MERCHANT.name()) && looksBroken(merchant)) {
            Transaction.Type type = "INCOME".equals(txnType) ? Transaction.Type.INCOME : Transaction.Type.EXPENSE;
            String fresh = CategoryRules.extractMerchantLabel(cleaned, type);
            if (fresh != null && !looksBroken(fresh) && fresh.length() <= MAX_MERCHANT_LENGTH) name = fresh;
        }
        return new Plan(cleaned, name);
    }

    /** No short name, a word with a run of four or more digits, or a leading rail word. */
    static boolean looksBroken(String label) {
        if (label == null || label.isBlank()) return true;
        if (REFERENCE_RUN.matcher(label).find()) return true;
        return RAIL_LEFTOVERS.contains(label.trim().split("\\s+")[0].toLowerCase(Locale.ROOT));
    }
}
