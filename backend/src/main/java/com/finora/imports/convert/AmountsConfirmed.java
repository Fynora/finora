package com.finora.imports.convert;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ContentIntegrityValidator;
import com.finora.imports.CreditCardFlowReconciliationValidator;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether the free converter may hand over a spreadsheet for a statement: Sid's rule (2026-10-11,
 * applied to the converter 2026-10-31) that amounts are used only once they are confirmed. There is
 * no person to confirm them for an anonymous visitor, so a check must.
 *
 * <p>Confirmed means, for the statement as a whole:
 * <ol>
 *   <li>{@link TrustPredicate} would not hold it (the same nine rules that hold an import), and</li>
 *   <li>every section with rows has at least one check that proves its amounts {@code VERIFIED}:
 *       {@code STATEMENT_TOTALS} (opening plus transactions reaches the printed closing balance),
 *       {@code BALANCE_CHAIN} (every printed running balance agrees), or for a card
 *       {@code CREDIT_CARD_FLOW_RECONCILIATION} (rows sum to the printed purchases and payments),
 *       and</li>
 *   <li>no section's {@code CONTENT_INTEGRITY} check failed. In the app the user decides that one;
 *       the converter has nobody to ask.</li>
 * </ol>
 *
 * <p>Measured before it shipped (PR 0, 2026-10-31, the local real corpus read the converter's way):
 * 31 of the 33 readable statements confirmed, and all 28 of those with a ground-truth file matched
 * it. None was confirmed and wrong.
 *
 * <p>Pure and static, like {@link TrustPredicate}.
 */
public final class AmountsConfirmed {

    /** The checks whose VERIFIED outcome proves a section's amounts. */
    static final Set<String> AMOUNT_PROOFS = Set.of(
            StatementTotalsValidator.RULE, BalanceChainValidator.RULE, CreditCardFlowReconciliationValidator.RULE);

    private AmountsConfirmed() {}

    /** What stood in the way, as machine-readable reasons; empty when confirmed. */
    public record Verdict(List<String> reasons, List<String> proofs) {
        public boolean confirmed() {
            return reasons.isEmpty();
        }
    }

    public static Verdict of(List<StagedAccountSection> sections, LocalDate today) {
        Set<String> reasons = new LinkedHashSet<>();
        Set<String> proofs = new LinkedHashSet<>();
        List<VerificationReport> reports = new ArrayList<>();
        List<LocalDate[]> periods = new ArrayList<>();
        for (StagedAccountSection section : sections) {
            reports.add(section.verification());
            periods.add(section.detectedAccount() == null ? null : new LocalDate[]{
                    section.detectedAccount().statementPeriodStart(), section.detectedAccount().statementPeriodEnd()});
        }
        HoldDecision hold = TrustPredicate.evaluate(reports, periods, today);
        if (hold.hold()) {
            hold.categories().forEach(category -> reasons.add("HOLD_" + category.name()));
        }
        boolean anyRows = false;
        for (StagedAccountSection section : sections) {
            if (section.rows() == null || section.rows().isEmpty()) continue;
            anyRows = true;
            List<VerificationFinding> findings = section.verification() == null
                    || section.verification().findings() == null ? List.of() : section.verification().findings();
            boolean proven = false;
            for (VerificationFinding finding : findings) {
                if (finding == null) continue;
                if (AMOUNT_PROOFS.contains(finding.rule()) && "VERIFIED".equals(finding.outcome())) {
                    proven = true;
                    proofs.add(finding.rule());
                }
                if (ContentIntegrityValidator.RULE.equals(finding.rule()) && "FAILED".equals(finding.outcome())) {
                    reasons.add("CONTENT_DAMAGED");
                }
            }
            if (!proven) reasons.add("NO_AMOUNT_PROOF");
        }
        if (!anyRows) reasons.add("NO_ROWS");
        return new Verdict(List.copyOf(reasons), List.copyOf(proofs));
    }
}
