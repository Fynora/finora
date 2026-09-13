package com.finora.imports.trust;

import com.finora.dto.ImportDto;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.DescriptionCorruptionValidator;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.SummaryTotalsValidator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether an extraction is trustworthy enough to reach a user's ledger unreviewed.
 *
 * <p>Seven conditions. Each is a signal the pipeline already computes, chosen because it is
 * evidence that a specific transaction is <em>wrong, missing, or corrupted</em> rather than
 * evidence that extraction was merely difficult:
 *
 * <ol>
 *   <li><b>Printed vs parsed count mismatch.</b> The document grades its own extraction -- the
 *       bank printed how many debits and credits it believes are there. Amounts-only mismatches
 *       are excluded: a wrong amount is a different defect from a missing row.</li>
 *   <li><b>A confirmed dropped transaction.</b> Only {@code PRE_HEADER_ACTIVITY_CANDIDATE}, the
 *       one dropped-row reason verified against real documents to mean a genuinely lost
 *       transaction rather than a merely unexplained row.</li>
 *   <li><b>Statement period integrity.</b> A period that ends before it starts, sits in the
 *       future, or spans more than {@value #MAX_PERIOD_DAYS} days did not come out of the
 *       document correctly.</li>
 *   <li><b>A systematic balance-chain break.</b> Only {@link BalanceChainValidator.Outcome#FAILED}
 *       -- {@code BalanceChainValidator}'s own systematic threshold (several rows AND at least
 *       half the checked pairs) disagreeing with the statement's own printed running balance. A
 *       single {@code WARNING}-level discrepancy is excluded: that validator's own doc comment
 *       documents real, legitimate ways one row can defeat the chain (a mid-statement summary
 *       line, a reordered same-day pair) without anything being wrong -- only the systematic case
 *       is evidence a value is actually wrong rather than a document with one unusual row.</li>
 *   <li><b>Column ambiguity.</b> A cell the document itself did not settle -- two amounts in one
 *       column, or both a credit and a debit column claiming the same row. Real, evidenced to
 *       silently misread a genuine deposit as an expense; rare on the real corpus this was
 *       measured against before shipping (see the PR that added this).</li>
 *   <li><b>Header-reconstruction uncertainty.</b> The parser had to guess-rebuild a header row it
 *       was not confident about -- a shaky header means the column-to-value mapping for the whole
 *       table is uncertain, not just one cell.</li>
 *   <li><b>Description corruption.</b> See {@link DescriptionCorruptionValidator} -- a
 *       transaction's own description reads as far longer and more prose-shaped than this
 *       document's peers, the shape a page footer or disclaimer takes when it merges into a real
 *       transaction's narration by mistake.</li>
 * </ol>
 *
 * <h2>What is still deliberately excluded</h2>
 * OCR provenance, duplicates, and missing account metadata are all observed and persisted, and
 * none of them hold an import.
 * <ul>
 *   <li><b>OCR provenance</b> is a real, higher-error-rate signal in principle, but this corpus
 *       could not measure its real production fire rate (every document sampled read natively, so
 *       the rate here is an artifact of the sample, not evidence it is rare) -- add it once
 *       production telemetry, not a curated sample, shows the real distribution.</li>
 *   <li><b>Duplicates</b> are not evidence the EXTRACTION is wrong -- they are evidence of
 *       overlapping data (a re-upload, a shared transfer between two accounts), which is normal
 *       and already has a better-fitting mechanism: {@code TransactionNormalizer} flags each
 *       likely-duplicate row individually and the user decides at confirm time, per row, in
 *       seconds. Routing that through an admin-review queue instead would be a worse experience
 *       for a case that was never a data-quality problem. It is also architecturally different
 *       from every signal above: it needs the user's existing account transactions, which this
 *       pure per-document function has no access to.</li>
 *   <li><b>Missing account metadata</b> says nothing about whether the dates and amounts are
 *       correct -- only that the account could not be auto-identified, which the user already
 *       resolves by picking or creating the account themselves. The highest-firing of the signals
 *       measured before this file's expansion (~17% of a real corpus sample), and holding on it
 *       would quarantine transactions that have nothing wrong with them.</li>
 * </ul>
 * Nor does the aggregate {@code ImportReliabilityStatus} gate: this reads the specific findings so
 * the verdict and the gate can be tuned independently. A <b>missing</b> period never holds either
 * -- corpus data showed that would quarantine most good imports. Each candidate above was added
 * only once a real-corpus measurement showed it firing rarely enough not to repeat that mistake;
 * see the PR that added them for the exact counts.
 *
 * <p>Pure, static and side-effect-free by design. It runs on the worker's success path, where an
 * exception would turn a merely-unverified import into a failed one, so every input is treated as
 * possibly null: a CSV import has no verification reports at all, and a section can carry no
 * period.
 */
public final class TrustPredicate {

    /** Beyond this, a "statement period" is not a statement period. Generous on purpose -- annual
     *  and 13-month statements are real, and this is looking for nonsense, not for unusual. */
    public static final long MAX_PERIOD_DAYS = 400;

    /**
     * The {@code suspectedCause} values that mean the COUNTS disagree, as opposed to the amounts.
     *
     * <p>An allow-list rather than "everything except AMOUNTS", deliberately. A cause added to
     * {@link SummaryTotalsValidator} later must not begin quarantining live imports the moment it
     * ships, before anyone has seen how often it fires -- that is the same observe-then-gate rule
     * every excluded signal above follows, applied to a signal that does not exist yet.
     *
     * <p>{@code PRINTED_ACTIVITY_WITH_ZERO_STAGED} is the extreme member and the easiest to miss:
     * the validator emits it with outcome WARNING rather than FAILED (its own comment explains
     * why -- the data did not fail validation, it never arrived), so anything gating on FAILED
     * would skip the case that validator itself calls the strongest evidence a read failed. It is
     * reachable on an otherwise-successful import because
     * {@code ExtractionCheck.rejectIfNothingWasExtracted} flattens all sections into one
     * whole-document view and throws only when the entire document staged nothing: a composite
     * statement whose second section staged zero rows while printing activity imports today, one
     * account short.
     */
    private static final Set<String> COUNT_MISMATCH_CAUSES = Set.of(
            "DIRECTION",
            "ROW_GROUPING",
            "MISSING_OR_EXTRA_ROWS",
            SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED);

    /** Key inside {@code ROW_ACCOUNTING}'s {@code droppedTransactionCandidateReasons} histogram.
     *  Mirrors {@code ImportReliabilityStatusDeriver}'s own constant; the literal is produced by
     *  {@code PdfTableLocator}. */
    private static final String PRE_HEADER_ACTIVITY_CANDIDATE = "PRE_HEADER_ACTIVITY_CANDIDATE";

    private static final String DROPPED_REASONS = "droppedTransactionCandidateReasons";
    private static final String SUSPECTED_CAUSE = "suspectedCause";

    private TrustPredicate() {}

    /** The machine-readable tag behind each of {@code evaluate}'s reason sentences -- see Plan 4's
     *  Decisions table for why {@code held_statements.hold_reason_categories} exists rather than
     *  parsing {@code trigger_summary}'s prose back apart. */
    public enum Category {
        COUNT_MISMATCH, DROPPED_TRANSACTION, PERIOD_INTEGRITY,
        BALANCE_CHAIN_DISCREPANCY, COLUMN_AMBIGUITY, HEADER_RECONSTRUCTION_UNCERTAIN,
        DESCRIPTION_CORRUPTION
    }

    /**
     * @param reports one report per account section, or null for an import that verified nothing
     * @param periods one {@code {start, end}} pair per account section; the array, or either
     *                element, may be null -- which is never on its own a reason to hold
     * @param today   the clock, injected so the future-period rule is testable
     */
    public static HoldDecision evaluate(List<ImportDto.VerificationReport> reports,
                                        List<LocalDate[]> periods,
                                        LocalDate today) {
        // Insertion-ordered and de-duplicating: several sections of one statement commonly fail
        // the same way, and telling an operator "the counts disagree" three times reads as three
        // separate problems.
        Set<String> reasons = new LinkedHashSet<>();
        Set<Category> categories = new LinkedHashSet<>();

        if (reports != null) {
            for (ImportDto.VerificationReport report : reports) {
                if (report == null) continue;
                // Per-report, not per-finding: this is a fact about the whole section's header,
                // not a fact any single VerificationFinding carries.
                if (report.headerReconstructionUncertain()) {
                    reasons.add("The column header had to be guess-rebuilt with low confidence");
                    categories.add(Category.HEADER_RECONSTRUCTION_UNCERTAIN);
                }
                if (report.findings() == null) continue;
                for (ImportDto.VerificationFinding finding : report.findings()) {
                    if (finding == null) continue;
                    countMismatch(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.COUNT_MISMATCH);
                    });
                    droppedTransaction(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.DROPPED_TRANSACTION);
                    });
                    balanceChainBroken(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.BALANCE_CHAIN_DISCREPANCY);
                    });
                    columnAmbiguity(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.COLUMN_AMBIGUITY);
                    });
                    descriptionCorruption(finding).ifPresent(r -> {
                        reasons.add(r);
                        categories.add(Category.DESCRIPTION_CORRUPTION);
                    });
                }
            }
        }
        if (periods != null) {
            for (LocalDate[] period : periods) {
                periodIntegrity(period, today).ifPresent(r -> {
                    reasons.add(r);
                    categories.add(Category.PERIOD_INTEGRITY);
                });
            }
        }

        return reasons.isEmpty() ? HoldDecision.RELEASE
                : new HoldDecision(true, List.copyOf(reasons), List.copyOf(categories));
    }

    /** Keyed on the cause rather than the outcome -- see {@link #COUNT_MISMATCH_CAUSES} for why
     *  the outcome is the wrong thing to filter on here. */
    private static Optional<String> countMismatch(ImportDto.VerificationFinding finding) {
        if (!SummaryTotalsValidator.RULE.equals(finding.rule()) || finding.details() == null) {
            return Optional.empty();
        }
        Object cause = finding.details().get(SUSPECTED_CAUSE);
        if (!(cause instanceof String named) || !COUNT_MISMATCH_CAUSES.contains(named)) {
            return Optional.empty();
        }
        return Optional.of("Printed and parsed transaction count disagree (" + named + ")");
    }

    private static Optional<String> droppedTransaction(ImportDto.VerificationFinding finding) {
        if (!RowAccountingValidator.RULE.equals(finding.rule()) || finding.details() == null) {
            return Optional.empty();
        }
        Object reasons = finding.details().get(DROPPED_REASONS);
        if (reasons instanceof Map<?, ?> map && map.containsKey(PRE_HEADER_ACTIVITY_CANDIDATE)) {
            return Optional.of("A transaction was likely dropped before the header row");
        }
        return Optional.empty();
    }

    /** Only {@code FAILED}, never {@code WARNING} -- see the class doc for why a single
     *  discrepancy is excluded (real, legitimate ways one row can defeat the chain without being
     *  wrong) while a systematic break is not. */
    private static Optional<String> balanceChainBroken(ImportDto.VerificationFinding finding) {
        if (!BalanceChainValidator.RULE.equals(finding.rule())) return Optional.empty();
        if (!"FAILED".equals(finding.outcome())) return Optional.empty();
        return Optional.of("The running balance does not reconcile with the statement's own printed figures");
    }

    private static Optional<String> columnAmbiguity(ImportDto.VerificationFinding finding) {
        if (!ColumnAmbiguityValidator.RULE.equals(finding.rule())) return Optional.empty();
        if (!"WARNING".equals(finding.outcome())) return Optional.empty();
        return Optional.of("A column's value was ambiguous and the document did not settle which reading is correct");
    }

    private static Optional<String> descriptionCorruption(ImportDto.VerificationFinding finding) {
        if (!DescriptionCorruptionValidator.RULE.equals(finding.rule())) return Optional.empty();
        if (!"WARNING".equals(finding.outcome())) return Optional.empty();
        return Optional.of("A transaction's description looks like it absorbed text that was never part of it");
    }

    private static Optional<String> periodIntegrity(LocalDate[] period, LocalDate today) {
        if (period == null || period.length != 2 || period[0] == null || period[1] == null) {
            // A missing period is not a defect this gates on -- see the class doc.
            return Optional.empty();
        }
        LocalDate start = period[0];
        LocalDate end = period[1];

        if (end.isBefore(start)) {
            return Optional.of("Statement period ends before it starts");
        }
        if (today != null && (start.isAfter(today) || end.isAfter(today))) {
            return Optional.of("Statement period is in the future");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
            return Optional.of("Statement period spans more than " + MAX_PERIOD_DAYS + " days");
        }
        return Optional.empty();
    }
}
