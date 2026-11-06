package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
 *       statements always print one) never silences a later balance-chain failure on it. A card
 *       summary disagreeing with itself is left out when the rows agree with it -- see
 *       {@link #rulesNotPassed}.</li>
 *   <li>{@link Reason#BLANK_DESCRIPTIONS} -- more than half the staged rows have no description,
 *       the Kotak symptom. Half, not "any": a genuine statement can carry the odd blank narration
 *       (a reversal line, a bare charge), and flagging every such file would bury the real ones.</li>
 *   <li>{@link Reason#STAGING_FAILED} -- the parser located a table (so a fingerprint exists) but
 *       staging still failed on our side (see {@link #isParserSideFailure}); a deliberate refusal
 *       such as "no activity in this period" does not flag.</li>
 *   <li>{@link Reason#HOLDER_NAME_UNREADABLE} -- a holder label's value could not be a name and was
 *       refused (HolderNameSanity), and no holder was found anywhere else. The statement still
 *       stages, with no holder: its transactions are unaffected, so the upload is not held, but the
 *       layout's holder rule needs a look. On a tester's Axis card statements a wrapped footer
 *       sentence ("...Cardholder's" / "name.") was read as the holder.</li>
 *   <li>{@link Reason#IDENTITY_CONFLICT} -- this layout was grouped automatically under one bank and
 *       account family, and a later statement of it was detected as another. See
 *       {@link LayoutProfileAutoLinker}.</li>
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

    public enum Reason { NEW_LAYOUT, VERIFICATION_NOT_PASSED, BLANK_DESCRIPTIONS, STAGING_FAILED, IDENTITY_CONFLICT,
        HOLDER_NAME_UNREADABLE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final LayoutReviewAlertService alertService;
    private final LayoutProfileAutoLinker autoLinker;

    public LayoutReviewService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                               LayoutReviewAlertService alertService, LayoutProfileAutoLinker autoLinker) {
        this.jdbc = jdbc;
        this.autoLinker = autoLinker;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.alertService = alertService;
    }

    /** A staging run that produced rows. {@code reports} holds one verification report per section
     *  (entries may be null -- "not checked"); {@code identity} is the statement's confident bank
     *  and account family, or null, and places the layout in its profile automatically. */
    public void onStaged(String fingerprint, String sourceFormat, List<StagedRow> rows,
                         List<VerificationReport> reports, String analysisReference, LayoutIdentity identity) {
        onStaged(fingerprint, sourceFormat, rows, reports, analysisReference, identity, false);
    }

    /** As above; {@code holderUnreadable} raises {@link Reason#HOLDER_NAME_UNREADABLE}. */
    public void onStaged(String fingerprint, String sourceFormat, List<StagedRow> rows,
                         List<VerificationReport> reports, String analysisReference, LayoutIdentity identity,
                         boolean holderUnreadable) {
        onStaged(fingerprint, sourceFormat, rows, reports, analysisReference, identity, holderUnreadable, false);
    }

    /** As above; {@code bankConflict} raises {@link Reason#IDENTITY_CONFLICT}: the file named a bank
     *  other than the one its layout's profile carries (ProfileBankFiller). The file's bank was
     *  used; a person should look at whether the layout sits in the right profile. */
    public void onStaged(String fingerprint, String sourceFormat, List<StagedRow> rows,
                         List<VerificationReport> reports, String analysisReference, LayoutIdentity identity,
                         boolean holderUnreadable, boolean bankConflict) {
        Set<String> reasons = new TreeSet<>();
        if (holderUnreadable) reasons.add(Reason.HOLDER_NAME_UNREADABLE.name());
        if (bankConflict) reasons.add(Reason.IDENTITY_CONFLICT.name());
        for (String rule : rulesNotPassed(reports)) reasons.add(Reason.VERIFICATION_NOT_PASSED.name() + ":" + rule);
        if (mostlyBlankDescriptions(rows)) reasons.add(Reason.BLANK_DESCRIPTIONS.name());
        record(fingerprint, sourceFormat, reasons, analysisReference, identity, findingSummaries(reports), reports);
    }

    /** A staging run that failed after the document's layout was fingerprinted. Flags the layout
     *  only for a failure on our side -- see {@link #isParserSideFailure}. The layout is still
     *  registered and counted either way. */
    public void onStagingFailed(String fingerprint, String sourceFormat, String analysisReference,
                                RuntimeException failure) {
        Set<String> reasons = isParserSideFailure(failure) ? Set.of(Reason.STAGING_FAILED.name()) : Set.of();
        record(fingerprint, sourceFormat, reasons, analysisReference, null, List.of(), null);
    }

    /**
     * Whether a staging failure is a gap on our side rather than a deliberate refusal. Measured on
     * the real corpus: a statement whose own summary shows no activity ({@code
     * IMPORT_NO_ACTIVITY_IN_PERIOD}) and a payment-app history ({@code IMPORT_PAYMENT_APP_HISTORY})
     * both fail staging on purpose, with a message telling the user what to do -- flagging them
     * would email an admin about Finora working as designed. Same line ImportJobWorker's held-imports
     * triage draws: only an unexpected exception, or "no table" / "no rows" on a document that got
     * far enough to be fingerprinted, is a parser problem.
     */
    static boolean isParserSideFailure(RuntimeException failure) {
        if (!(failure instanceof com.finora.exception.ApiException api)) return failure != null;
        return api.getCode() == com.finora.exception.ErrorCode.IMPORT_NO_HEADER_DETECTED
                || api.getCode() == com.finora.exception.ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND;
    }

    /**
     * The rules that reported WARNING or FAILED in any section, by name -- except a card summary
     * disagreeing with itself ({@link CreditCardStatementTotalsValidator}) in a section whose rows
     * agree with that summary's purchases and payments ({@link CreditCardFlowReconciliationValidator}
     * VERIFIED). Sid's decision (2026-10-02): the rows are what reach the ledger, the import went
     * through, and calling it "needs review" was wrong. Judged per section: one section agreeing
     * says nothing about another.
     */
    static Set<String> rulesNotPassed(List<VerificationReport> reports) {
        Set<String> rules = new TreeSet<>();
        for (NotPassed notPassed : notPassedFindings(reports)) {
            rules.add(notPassed.finding().rule() == null ? "UNKNOWN_RULE" : notPassed.finding().rule());
        }
        return rules;
    }

    /** A finding that reported WARNING or FAILED, with the section it came from. */
    record NotPassed(int section, VerificationFinding finding) {}

    /** The findings {@link #rulesNotPassed} names, with the card-summary exemption described there. */
    static List<NotPassed> notPassedFindings(List<VerificationReport> reports) {
        List<NotPassed> found = new ArrayList<>();
        if (reports == null) return found;
        for (int section = 0; section < reports.size(); section++) {
            VerificationReport report = reports.get(section);
            if (report == null || report.findings() == null) continue;
            boolean cardRowsAgree = report.findings().stream().filter(Objects::nonNull).anyMatch(f ->
                    CreditCardFlowReconciliationValidator.RULE.equals(f.rule()) && "VERIFIED".equals(f.outcome()));
            for (VerificationFinding f : report.findings()) {
                if (f == null) continue;
                if (!"WARNING".equals(f.outcome()) && !"FAILED".equals(f.outcome())) continue;
                if (cardRowsAgree && CreditCardStatementTotalsValidator.RULE.equals(f.rule())) continue;
                found.add(new NotPassed(section, f));
            }
        }
        return found;
    }

    /**
     * One plain line per finding that did not pass, for the alert email: the rule, its outcome and,
     * for the balance chain, how many of the checked rows disagreed and whether the gaps cancel.
     *
     * <p>Counts and yes/no facts only. The balance chain's {@code discrepancies} list holds the
     * customer's balances and gaps; it is measured for its size here and never read, because the
     * email carries "not a single transaction" (see {@link LayoutReviewAlertService}).
     */
    static List<String> findingSummaries(List<VerificationReport> reports) {
        List<NotPassed> found = notPassedFindings(reports);
        boolean severalSections = reports != null && reports.size() > 1;
        List<String> lines = new ArrayList<>();
        for (NotPassed notPassed : found) {
            VerificationFinding f = notPassed.finding();
            StringBuilder line = new StringBuilder(f.rule() == null ? "UNKNOWN_RULE" : f.rule());
            if (severalSections) line.append(" (section ").append(notPassed.section()).append(')');
            line.append(" — ").append(f.outcome());
            Map<String, Object> details = f.details();
            if (details != null && details.get("discrepancies") instanceof List<?> disagreeing
                    && !disagreeing.isEmpty() && details.get("rowsChecked") instanceof Number checked) {
                line.append(": ").append(disagreeing.size()).append(" of ").append(checked.longValue())
                        .append(" rows disagree with the printed balance");
                if (Boolean.TRUE.equals(details.get("gapsCancel"))) line.append("; the gaps cancel out");
                line.append(reorderClause(details.get("reorderHypothesis")));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * "; rows 257, 258, 259 add up if taken in the order 258, 257, 259" for each run of rows whose
     * re-ordering the validator found to reconcile in exactly one way, or "" when none did.
     *
     * <p>Positions only, counted from one like every row number an operator reads. Anything in the
     * hypothesis that is not a whole number is skipped rather than printed: this builds a sentence
     * from values the email must never carry.
     */
    static String reorderClause(Object hypothesis) {
        if (!(hypothesis instanceof Map<?, ?> found) || !(found.get("windows") instanceof List<?> windows)) return "";
        StringBuilder clause = new StringBuilder();
        boolean anyReconciles = false;
        for (Object element : windows) {
            if (element instanceof Map<?, ?> w && Boolean.TRUE.equals(w.get("reconciles"))) anyReconciles = true;
        }
        // A tried-and-failed result is worth one line too: it rules re-ordering out from a phone.
        if (!anyReconciles) {
            return Boolean.TRUE.equals(found.get("tested"))
                    ? "; re-ordering the disagreeing rows does not explain it" : "";
        }
        for (Object element : windows) {
            if (!(element instanceof Map<?, ?> window) || !Boolean.TRUE.equals(window.get("reconciles"))) continue;
            List<Integer> rows = wholeNumbers(window.get("rows"));
            List<Integer> order = wholeNumbers(window.get("order"));
            if (rows.isEmpty() || order.size() != rows.size()) continue;
            clause.append("; rows ").append(oneBased(rows.stream().sorted().toList()))
                    .append(" add up if taken in the order ").append(oneBased(order));
        }
        return clause.toString();
    }

    private static List<Integer> wholeNumbers(Object value) {
        List<Integer> numbers = new ArrayList<>();
        if (!(value instanceof List<?> items)) return numbers;
        for (Object item : items) if (item instanceof Integer position && position >= 0) numbers.add(position);
        return numbers;
    }

    private static String oneBased(List<Integer> positions) {
        return positions.stream().map(position -> String.valueOf(position + 1))
                .collect(java.util.stream.Collectors.joining(", "));
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

    private void record(String fingerprint, String sourceFormat, Set<String> reasons, String analysisReference,
                        LayoutIdentity identity, List<String> findingLines, List<VerificationReport> reports) {
        if (fingerprint == null || fingerprint.isBlank()) return;
        Flagged flagged;
        try {
            if (isHeaderlessFingerprint(fingerprint, sourceFormat)) return;
            flagged = ownTransaction.execute(status -> recordInTransaction(
                    fingerprint, sourceFormat, new TreeSet<>(reasons), analysisReference, identity));
        } catch (RuntimeException e) {
            log.warn("Could not record layout review state for {}; the upload is unaffected.", fingerprint, e);
            return;
        }
        if (flagged != null && flagged.newlyRaised()) {
            try {
                alertService.alertLayoutNeedsReview(fingerprint, flagged.reasons(), analysisReference, flagged.profile(),
                        findingLines, LayoutAlertTier.of(flagged.reasons(), reports));
            } catch (RuntimeException e) {
                log.warn("Could not send the layout review alert for {}.", fingerprint, e);
            }
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String toJson(Map<String, String> sources) {
        try {
            return JSON.writeValueAsString(sources);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A map of strings always serialises", e);
        }
    }

    /** What the flag became, whether this call is the one that raised it, and the profile the
     *  layout sits in ("Kotak Mahindra Bank — Credit Card v2"), or null. */
    record Flagged(boolean newlyRaised, List<String> reasons, String profile) {}

    private Flagged recordInTransaction(String fingerprint, String sourceFormat, Set<String> reasons,
                                        String analysisReference, LayoutIdentity identity) {
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
        // Grouped before the flag is decided, so a clean staging of a known layout still joins its
        // profile -- and a conflicting identity becomes a reason like any other.
        if (autoLinker.link(fingerprint, identity) == LayoutProfileAutoLinker.Outcome.CONFLICT) {
            reasons.add(Reason.IDENTITY_CONFLICT.name());
        }
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

        Set<String> alreadyFlagged = wasFlagged
                ? new TreeSet<>(RegisteredLayout.reasonsOf((String) current.get("review_reasons")))
                : Set.of();
        Set<String> merged = new TreeSet<>(fresh);
        merged.addAll(alreadyFlagged);
        // This run is the source of the reasons it adds to the flag (V270). A reason already on the
        // flag keeps the source it has -- the run that first raised it -- or, flagged before V270,
        // stays without one rather than being credited to this run.
        Map<String, String> sources = new TreeMap<>();
        if (analysisReference != null && !analysisReference.isBlank()) {
            for (String r : fresh) if (!alreadyFlagged.contains(r)) sources.put(r, analysisReference);
        }
        jdbc.update("""
                UPDATE layout_registry
                SET needs_review = TRUE,
                    review_reasons = ?,
                    review_flagged_at = CASE WHEN needs_review THEN review_flagged_at ELSE ? END,
                    review_analysis_reference = ?,
                    review_reason_sources = NULLIF(?::jsonb || CASE WHEN needs_review
                            THEN COALESCE(review_reason_sources, '{}'::jsonb) ELSE '{}'::jsonb END, '{}'::jsonb),
                    updated_at = now()
                WHERE fingerprint = ?
                """, String.join(",", merged), now, analysisReference, toJson(sources), fingerprint);
        List<String> profile = jdbc.queryForList("""
                SELECT p.name || ' v' || r.profile_version FROM layout_registry r
                JOIN layout_profile p ON p.id = r.profile_id WHERE r.fingerprint = ?
                """, String.class, fingerprint);
        return new Flagged(!wasFlagged, List.copyOf(merged), profile.isEmpty() ? null : profile.get(0));
    }
}
