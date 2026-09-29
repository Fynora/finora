package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.entity.RegisteredLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts a statement layout in front of an admin the moment staging shows it needs one (V243).
 *
 * <h2>Why at staging, not at confirm</h2>
 *
 * <p>A newer Kotak credit-card layout reached production with every description blank and two
 * verification warnings. The user saw a broken review screen and did not confirm -- which is
 * exactly when {@link LayoutRegistryService#observe} never runs, so the layout was never even
 * registered, let alone reported. A layout that stages badly is most likely to be one nobody
 * confirms. This service therefore runs on every staging attempt, successful or failed.
 *
 * <h2>What flags a layout</h2>
 *
 * <ul>
 *   <li>{@link Reason#NEW_LAYOUT} -- the registry had no row for this fingerprint.</li>
 *   <li>{@code VERIFICATION_NOT_PASSED:<RULE>} -- a verification rule reported WARNING or FAILED.
 *       One reason per rule, so acknowledging a layout's routine totals warning (two real Axis
 *       statements always print one) never silences a later balance-chain failure on it.</li>
 *   <li>{@link Reason#BLANK_DESCRIPTIONS} -- more than half the staged rows have no description,
 *       the Kotak symptom. Half, not "any": a genuine statement can carry the odd blank narration
 *       (a reversal line, a bare charge), and flagging every such file would bury the real ones.</li>
 *   <li>{@link Reason#STAGING_FAILED} -- the parser located a table (so a fingerprint exists) but
 *       staging still failed.</li>
 * </ul>
 *
 * <p><b>Documents with no recognised column headers are skipped.</b> Every such document -- a
 * "no table found" failure, a headerless layout -- hashes to the same fingerprint whatever bank it
 * came from, so it would pile onto one fake layout, and once an admin resolved that, no later one
 * would ever alert again. They are not a layout the registry can describe.
 *
 * <p>A reason already {@linkplain RegisteredLayout#resolveReview acknowledged} by an operator does
 * not flag the layout again; only a new one does. Otherwise a layout that always prints (say) a
 * totals warning would re-alert on every upload after every resolve, and the alerts would stop
 * being read.
 *
 * <h2>Never costs an upload</h2>
 *
 * <p>Every call is its own transaction and swallows its own failures -- the same rule
 * {@link LayoutRegistryService} and the held-item alerts follow. The alert goes out after that
 * transaction commits, and only when the flag changed from clear to raised, so two uploads of a
 * broken layout produce one email.
 */
@Service
public class LayoutReviewService {

    private static final Logger log = LoggerFactory.getLogger(LayoutReviewService.class);

    public enum Reason { NEW_LAYOUT, VERIFICATION_NOT_PASSED, BLANK_DESCRIPTIONS, STAGING_FAILED }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final LayoutReviewAlertService alertService;

    public LayoutReviewService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               LayoutReviewAlertService alertService) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.alertService = alertService;
    }

    /** A staging run that produced rows. {@code reports} holds one verification report per section
     *  (entries may be null -- "not checked"). */
    public void onStaged(String fingerprint, String sourceFormat, List<StagedRow> rows,
                         List<VerificationReport> reports, String analysisReference) {
        Set<String> reasons = new TreeSet<>();
        for (String rule : rulesNotPassed(reports)) reasons.add(Reason.VERIFICATION_NOT_PASSED.name() + ":" + rule);
        if (mostlyBlankDescriptions(rows)) reasons.add(Reason.BLANK_DESCRIPTIONS.name());
        record(fingerprint, sourceFormat, reasons, analysisReference);
    }

    /** A staging run that failed after the document's layout was fingerprinted. */
    public void onStagingFailed(String fingerprint, String sourceFormat, String analysisReference) {
        record(fingerprint, sourceFormat, Set.of(Reason.STAGING_FAILED.name()), analysisReference);
    }

    /** The rules that reported WARNING or FAILED in any section, by name. */
    static Set<String> rulesNotPassed(List<VerificationReport> reports) {
        Set<String> rules = new TreeSet<>();
        if (reports == null) return rules;
        reports.stream().filter(Objects::nonNull)
                .flatMap(r -> r.findings() == null ? java.util.stream.Stream.empty() : r.findings().stream())
                .filter(f -> "WARNING".equals(f.outcome()) || "FAILED".equals(f.outcome()))
                .forEach(f -> rules.add(f.rule() == null ? "UNKNOWN_RULE" : f.rule()));
        return rules;
    }

    /** The fingerprint every document with no recognised headers shares -- see the class comment. */
    static boolean isHeaderlessFingerprint(String fingerprint, String sourceFormat) {
        return fingerprint.equals(new DocumentContext(sourceFormat, "").buildFingerprint());
    }

    static boolean mostlyBlankDescriptions(List<StagedRow> rows) {
        if (rows == null || rows.isEmpty()) return false;
        long blank = rows.stream().filter(r -> r.description() == null || r.description().isBlank()).count();
        return blank * 2 > rows.size();
    }

    private void record(String fingerprint, String sourceFormat, Set<String> reasons, String analysisReference) {
        if (fingerprint == null || fingerprint.isBlank()) return;
        Flagged flagged;
        try {
            if (isHeaderlessFingerprint(fingerprint, sourceFormat)) return;
            flagged = ownTransaction.execute(status -> recordInTransaction(
                    fingerprint, sourceFormat, new TreeSet<>(reasons), analysisReference));
        } catch (RuntimeException e) {
            log.warn("Could not record layout review state for {}; the upload is unaffected.", fingerprint, e);
            return;
        }
        if (flagged != null && flagged.newlyRaised()) {
            try {
                alertService.alertLayoutNeedsReview(fingerprint, flagged.reasons(), analysisReference);
            } catch (RuntimeException e) {
                log.warn("Could not send the layout review alert for {}.", fingerprint, e);
            }
        }
    }

    /** What the flag became, and whether this call is the one that raised it. */
    record Flagged(boolean newlyRaised, List<String> reasons) {}

    private Flagged recordInTransaction(String fingerprint, String sourceFormat, Set<String> reasons,
                                        String analysisReference) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // One statement for the insert-or-count, for the same race reason
        // RegisteredLayoutRepository.observe gives: two first uploads of an unseen layout must not
        // both insert. xmax = 0 is PostgreSQL's marker for "this row was inserted, not updated".
        Boolean inserted = jdbc.queryForObject("""
                INSERT INTO layout_registry (id, fingerprint, source_format, status, first_seen, last_seen,
                                             observation_count, staging_count, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 'OBSERVED', ?, ?, 0, 1, now(), now())
                ON CONFLICT (fingerprint) DO UPDATE SET
                    source_format = COALESCE(layout_registry.source_format, EXCLUDED.source_format),
                    first_seen    = LEAST(layout_registry.first_seen, EXCLUDED.first_seen),
                    last_seen     = GREATEST(layout_registry.last_seen, EXCLUDED.last_seen),
                    staging_count = layout_registry.staging_count + 1,
                    updated_at    = now()
                RETURNING (xmax = 0)
                """, Boolean.class, fingerprint, sourceFormat, now, now);
        if (Boolean.TRUE.equals(inserted)) reasons.add(Reason.NEW_LAYOUT.name());
        if (reasons.isEmpty()) return null;

        // Locked read of the current flag, so the "was it clear before" answer and the update are
        // one decision even with two uploads finishing together.
        var current = jdbc.queryForMap("""
                SELECT needs_review, review_reasons, acknowledged_reasons
                FROM layout_registry WHERE fingerprint = ? FOR UPDATE
                """, fingerprint);
        boolean wasFlagged = Boolean.TRUE.equals(current.get("needs_review"));
        Set<String> acknowledged = new TreeSet<>(RegisteredLayout.reasonsOf((String) current.get("acknowledged_reasons")));

        List<String> fresh = new ArrayList<>();
        for (String r : reasons) if (!acknowledged.contains(r)) fresh.add(r);
        if (fresh.isEmpty()) return null;

        Set<String> merged = new TreeSet<>(fresh);
        if (wasFlagged) merged.addAll(RegisteredLayout.reasonsOf((String) current.get("review_reasons")));
        jdbc.update("""
                UPDATE layout_registry
                SET needs_review = TRUE,
                    review_reasons = ?,
                    review_flagged_at = CASE WHEN needs_review THEN review_flagged_at ELSE ? END,
                    review_analysis_reference = ?,
                    updated_at = now()
                WHERE fingerprint = ?
                """, String.join(",", merged), now, analysisReference, fingerprint);
        return new Flagged(!wasFlagged, List.copyOf(merged));
    }
}
