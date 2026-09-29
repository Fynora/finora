package com.finora.imports;

import com.finora.util.BankRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Groups the layouts the registry already holds into automatic profiles (V244), from evidence
 * already stored -- so existing layouts, like the older Kotak credit-card one, need no operator work.
 *
 * <h2>Evidence</h2>
 * <ul>
 *   <li>Statement analysis sessions: the bank and product detected when the statement was staged
 *       (recorded since V239).</li>
 *   <li>Confirmed imports: the bank and type of the account each import went into. Older than the
 *       analysis evidence, so it covers layouts staged before V239.</li>
 * </ul>
 * Both reduce to a {@link LayoutIdentity} key. A layout whose evidence yields exactly one key is
 * linked through {@link LayoutProfileAutoLinker}, in first-seen order so version numbers follow
 * the order the formats appeared. A layout whose evidence yields two or more keys is not grouped:
 * it is flagged for review ({@code IDENTITY_CONFLICT}) without an email -- this is a one-time sweep,
 * and the review queue is where it will be seen.
 *
 * <p>Reads evidence only for undecided layouts, so a start after everything is grouped costs two
 * small queries. Runs once per application start, idempotently: it only ever touches a layout nobody has
 * decided about ({@code profile_id IS NULL AND profile_link_source IS NULL}), and each layout is its
 * own transaction. It can never fail startup.
 */
@Component
public class LayoutProfileBackfill {

    private static final Logger log = LoggerFactory.getLogger(LayoutProfileBackfill.class);

    private final JdbcTemplate jdbc;
    private final LayoutProfileAutoLinker linker;
    private final TransactionTemplate perLayout;
    private final boolean enabled;

    public LayoutProfileBackfill(JdbcTemplate jdbc, LayoutProfileAutoLinker linker,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${finora.layout-profiles.backfill-on-startup:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.linker = linker;
        this.perLayout = new TransactionTemplate(transactionManager);
        this.perLayout.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    /** What one sweep did, for the log line and for tests. */
    public record Result(int linked, int conflicts, int noEvidence) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) return;
        try {
            Result result = run();
            log.info("Layout profile backfill: {} linked, {} with conflicting evidence flagged, {} without evidence",
                    result.linked(), result.conflicts(), result.noEvidence());
        } catch (RuntimeException e) {
            log.warn("Layout profile backfill did not complete; it will run again on the next start.", e);
        }
    }

    public Result run() {
        List<Map<String, Object>> undecided = jdbc.queryForList("""
                SELECT fingerprint, source_format FROM layout_registry
                WHERE profile_id IS NULL AND profile_link_source IS NULL
                ORDER BY first_seen, fingerprint
                """);
        if (undecided.isEmpty()) return new Result(0, 0, 0);
        Map<String, Set<String>> keysByFingerprint = new HashMap<>();
        Map<String, LayoutIdentity> identityByKey = new HashMap<>();
        collectFromAnalysisSessions(keysByFingerprint, identityByKey);
        collectFromConfirmedImports(keysByFingerprint, identityByKey);

        int linked = 0, conflicts = 0, noEvidence = 0;
        for (Map<String, Object> row : undecided) {
            String fingerprint = (String) row.get("fingerprint");
            String sourceFormat = (String) row.get("source_format");
            if (sourceFormat != null && LayoutReviewService.isHeaderlessFingerprint(fingerprint, sourceFormat)) {
                noEvidence++;
                continue;
            }
            Set<String> keys = keysByFingerprint.getOrDefault(fingerprint, Set.of());
            if (keys.isEmpty()) {
                noEvidence++;
            } else if (keys.size() > 1) {
                perLayout.executeWithoutResult(status -> flagConflict(fingerprint));
                conflicts++;
            } else {
                LayoutIdentity identity = identityByKey.get(keys.iterator().next());
                LayoutProfileAutoLinker.Outcome outcome = perLayout.execute(status -> linker.link(fingerprint, identity));
                if (outcome == LayoutProfileAutoLinker.Outcome.LINKED) linked++;
                else if (outcome == LayoutProfileAutoLinker.Outcome.CONFLICT) {
                    perLayout.executeWithoutResult(status -> flagConflict(fingerprint));
                    conflicts++;
                }
            }
        }
        return new Result(linked, conflicts, noEvidence);
    }

    private void collectFromAnalysisSessions(Map<String, Set<String>> keysByFingerprint,
                                             Map<String, LayoutIdentity> identityByKey) {
        Map<String, String> bankIdByName = new HashMap<>();
        for (BankRegistry.BankInfo bank : BankRegistry.all()) {
            if (bank.officialName() != null) bankIdByName.put(bank.officialName(), bank.id());
        }
        jdbc.query("""
                SELECT DISTINCT layout_fingerprint, bank_name, statement_type FROM statement_analysis_sessions
                WHERE identity_checked AND bank_name IS NOT NULL AND statement_type IS NOT NULL
                  AND layout_fingerprint IN (SELECT fingerprint FROM layout_registry WHERE profile_id IS NULL AND profile_link_source IS NULL)
                """, rs -> {
            String bankId = bankIdByName.get(rs.getString("bank_name"));
            if (bankId == null) return;
            Set<String> families = new TreeSet<>();
            for (String product : rs.getString("statement_type").split(",")) {
                String family = LayoutIdentity.familyOfProduct(product);
                if (family == null) return; // a product with no account family: no confident identity
                families.add(family);
            }
            add(keysByFingerprint, identityByKey, rs.getString("layout_fingerprint"),
                    new LayoutIdentity(bankId, rs.getString("bank_name"), families));
        });
    }

    private void collectFromConfirmedImports(Map<String, Set<String>> keysByFingerprint,
                                             Map<String, LayoutIdentity> identityByKey) {
        jdbc.query("""
                SELECT DISTINCT si.layout_fingerprint, a.bank_id, a.account_type
                FROM statement_imports si JOIN accounts a ON a.id = si.account_id
                WHERE a.bank_id <> 'OTHER' AND si.layout_fingerprint IN (SELECT fingerprint FROM layout_registry WHERE profile_id IS NULL AND profile_link_source IS NULL)
                """, rs -> {
            BankRegistry.BankInfo bank = BankRegistry.get(rs.getString("bank_id"));
            if (bank == null || bank.officialName() == null || BankRegistry.UNKNOWN_ID.equals(bank.id())) return;
            String family = LayoutIdentity.familyOfAccountType(rs.getString("account_type"));
            if (family == null) return;
            add(keysByFingerprint, identityByKey, rs.getString("layout_fingerprint"),
                    new LayoutIdentity(bank.id(), bank.officialName(), Set.of(family)));
        });
    }

    private static void add(Map<String, Set<String>> keysByFingerprint, Map<String, LayoutIdentity> identityByKey,
                            String fingerprint, LayoutIdentity identity) {
        keysByFingerprint.computeIfAbsent(fingerprint, f -> new TreeSet<>()).add(identity.key());
        identityByKey.putIfAbsent(identity.key(), identity);
    }

    /** Raises the review flag with IDENTITY_CONFLICT unless an operator already acknowledged it. */
    private void flagConflict(String fingerprint) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT needs_review, review_reasons, acknowledged_reasons FROM layout_registry WHERE fingerprint = ? FOR UPDATE",
                fingerprint);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        String reason = LayoutReviewService.Reason.IDENTITY_CONFLICT.name();
        if (com.finora.entity.RegisteredLayout.reasonsOf((String) row.get("acknowledged_reasons")).contains(reason)) return;
        Set<String> merged = new TreeSet<>(List.of(reason));
        if (Boolean.TRUE.equals(row.get("needs_review"))) {
            merged.addAll(com.finora.entity.RegisteredLayout.reasonsOf((String) row.get("review_reasons")));
        }
        jdbc.update("""
                UPDATE layout_registry
                SET needs_review = TRUE, review_reasons = ?,
                    review_flagged_at = CASE WHEN needs_review THEN review_flagged_at ELSE now() END,
                    updated_at = now()
                WHERE fingerprint = ?
                """, String.join(",", merged), fingerprint);
    }
}
