package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks a credit-card statement's own billing equation against itself:
 *
 * <pre>
 *   previousBalance + purchases + cashAdvances + fees - paymentsAndCredits == totalAmountDue
 * </pre>
 *
 * <p><b>The credit-card counterpart of {@link StatementTotalsValidator}, not a second opinion on
 * it.</b> That validator reconciles a savings statement's printed opening/closing balance against
 * the SUM OF PARSED TRANSACTION ROWS. This validator never reads a transaction row at all — every
 * field on both sides of the equation comes from the same billing-summary panel the bank prints
 * about itself (see {@link com.finora.imports.pdf.CreditCardSummaryExtractor}). That is deliberate:
 * the Credit Card Direction Evidence Study found half the real credit-card corpus has a
 * transaction TABLE that does not form correctly (ICICI CC, HDFC — see the Open Investigations in
 * the architecture doc), while their summary panels may still be readable. A validator that needed
 * parsed rows would be exactly as broken as the table it depends on; this one is not, on purpose.
 *
 * <p><b>What a mismatch actually means here.</b> The bank's own printed numbers necessarily agree
 * with each other on the real document — a mismatch means THIS EXTRACTION misread one of the five
 * component fields (most likely a column-anchor mismatch on the summary grid, the same class of bug
 * {@link com.finora.imports.pdf.StatementSummaryExtractor} was built defensively against). That is
 * why this reports {@code WARNING}, never {@code FAILED}: unlike a savings statement's balance
 * chain, a mismatch here does not implicate the customer's transactions at all, only this
 * extraction's own reading of the summary panel. (One measured exception to "necessarily agree":
 * a real Axis summary that left a refund out of its own payments and credits figures. That case
 * is recognised only with the rows in hand -- see {@link #inLightOfTheRows}.)
 *
 * <p><b>Never infers a missing field or corrects a transaction.</b> If the panel does not print
 * previous balance, purchases, payments/credits, and the total amount due, this reports
 * {@code NOT_APPLICABLE} rather than guessing — the same "missing validation is safer than a wrong
 * one" discipline the Credit Card Direction Evidence Study itself was built around. Cash advances
 * and fees are the two exceptions, treated as zero when absent rather than blocking the check —
 * see {@link CreditCardSummaryEvidence#hasReconcilableFields()}'s own doc comment for the real
 * document (AU) that motivated extending that treatment from fees to cash advances too.
 *
 * <p><b>A cross-strategy disagreement outranks the equation check.</b> {@code extract} always runs
 * both GRID and INLINE_LABEL_VALUE, and {@link CreditCardSummaryEvidence#conflictingFields()} names
 * any field where they found different numbers. That is checked FIRST, before either
 * {@code NOT_APPLICABLE} or the equation math: two independent readings disagreeing is itself proof
 * one of them is wrong, regardless of whether the winning strategy's own result looks complete or
 * would otherwise have balanced.
 */
@Component
public class CreditCardStatementTotalsValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "CREDIT_CARD_STATEMENT_TOTALS";

    public ImportDto.VerificationFinding check(CreditCardSummaryEvidence summary) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (summary != null && !summary.conflictingFields().isEmpty()) {
            details.put("conflictingFields", summary.conflictingFields());
            details.put("reason", "SUMMARY_EXTRACTION_CONFLICT: GRID and INLINE_LABEL_VALUE read "
                    + "different values for the same field(s) on this statement's billing-summary "
                    + "panel. At least one of the two readings is wrong, so neither is trusted here.");
            return new ImportDto.VerificationFinding(RULE, "WARNING", details);
        }

        // Before the amount-due equation, never after: a panel of this kind sits beside a separately
        // printed amount due, so it has every field that equation asks for and would be compared
        // against a figure it was never meant to add up to.
        if (summary != null && summary.hasOutstandingEquation()) {
            return checkOutstandingEquation(summary, details);
        }

        if (summary == null || !summary.hasReconcilableFields()) {
            details.put("reason", "This statement's billing-summary panel did not print enough of "
                    + "previous balance, purchases, and payments/credits, alongside the total amount "
                    + "due, to check them against each other.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        BigDecimal cashAdvances = summary.cashAdvances() == null ? BigDecimal.ZERO : summary.cashAdvances();
        BigDecimal fees = summary.fees() == null ? BigDecimal.ZERO : summary.fees();
        BigDecimal expectedTotalAmountDue = summary.previousBalance()
                .add(summary.purchases())
                .add(cashAdvances)
                .add(fees)
                .subtract(summary.paymentsAndCredits());
        BigDecimal difference = summary.totalAmountDue().subtract(expectedTotalAmountDue);

        // extractionMethod: not persisted (see the allowlist), but available on the live finding for
        // debugging and user explanation -- which strategy read these numbers is real information,
        // just not one this validator needs to decide VERIFIED/WARNING/NOT_APPLICABLE.
        details.put("extractionMethod", summary.extractionMethod().name());
        details.put("previousBalance", summary.previousBalance());
        details.put("purchases", summary.purchases());
        details.put("cashAdvances", cashAdvances);
        details.put("fees", fees);
        details.put("paymentsAndCredits", summary.paymentsAndCredits());
        details.put("totalAmountDue", summary.totalAmountDue());
        details.put("expectedTotalAmountDue", expectedTotalAmountDue);
        details.put("difference", difference);

        if (difference.signum() == 0) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }
        // A total due printed in whole rupees while the figures it is built from carry paise: a
        // real IndusInd card prints its total due as the exact figure (also printed there, as
        // "Total Outstanding") with the paise dropped. That one example fits
        // rounding down and rounding to the nearest rupee alike, so neither direction is assumed:
        // the whole rupee on either side of the exact figure counts. An exact figure that is itself
        // whole has nothing to round, so it must match exactly, and anything a rupee or more away
        // is still a misread.
        if (isWholeRupees(summary.totalAmountDue()) && !isWholeRupees(expectedTotalAmountDue)
                && difference.abs().compareTo(BigDecimal.ONE) < 0) {
            details.put("totalAmountDueRoundedToRupee", true);
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("explanation", "The previous balance, purchases, cash advances, fees, and "
                + "payments/credits this statement prints about itself do not add up to its own "
                + "printed total amount due. This does not implicate any parsed transaction -- no "
                + "transaction row was read to produce this finding -- it means this extraction "
                + "misread one of the statement's own summary figures.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    /**
     * This rule's finding, read again once {@link CreditCardFlowReconciliationValidator} has
     * checked the rows -- the one case where the rows explain why the summary does not add up.
     *
     * <p>{@link #check} sees the summary alone, so a summary whose figures do not reach its own
     * total can only be reported as a misread. A real Axis card showed a second cause: its summary
     * counted a refund in neither its payments nor its credits figure, while its total due
     * reflects it. There the row check verifies the rows against the two balances and names the
     * credits the summary left out ({@code creditsNotCountedInSummary}). When that amount is
     * exactly this rule's own gap, nothing was misread, and warning would put "review recommended"
     * on a statement whose every amount is accounted for. The finding becomes
     * {@code NOT_APPLICABLE} with the reason and the same figures.
     *
     * <p>Anything else is returned untouched: a gap of any other size, a row check that did not
     * verify, and a conflict between two readings (which carries no gap at all) all still warn.
     */
    public static ImportDto.VerificationFinding inLightOfTheRows(ImportDto.VerificationFinding totals,
                                                                 ImportDto.VerificationFinding rowCheck) {
        if (totals == null || rowCheck == null || totals.details() == null || rowCheck.details() == null
                || !"WARNING".equals(totals.outcome()) || !"VERIFIED".equals(rowCheck.outcome())) {
            return totals;
        }
        if (!(rowCheck.details().get("creditsNotCountedInSummary") instanceof BigDecimal leftOut)
                || !(totals.details().get("difference") instanceof BigDecimal gap)
                || gap.negate().compareTo(leftOut) != 0) {
            return totals;
        }
        Map<String, Object> details = new LinkedHashMap<>(totals.details());
        details.remove("explanation");
        details.put("reason", SUMMARY_LEAVES_OUT_A_CREDIT);
        return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
    }

    /** Kept under the 200 characters the stored copy of a reason is cut to. */
    static final String SUMMARY_LEAVES_OUT_A_CREDIT = "SUMMARY_LEAVES_OUT_A_CREDIT: the summary's "
            + "payments and credits leave out a credit its own total includes. The rows carry the "
            + "previous balance to that total exactly.";

    /**
     * The same arithmetic for a panel that ends on its outstanding balance rather than its amount
     * due -- a real SBI card's "Account Summary", whose "Total Outstanding" also counts instalment
     * principal not yet billed and so is larger than the "Total Amount Due" printed in another box.
     * That statement prints the outstanding total in whole rupees over figures carrying paise, so
     * the allowance the amount-due equation makes for a rounded total applies here unchanged.
     */
    private ImportDto.VerificationFinding checkOutstandingEquation(CreditCardSummaryEvidence summary,
                                                                   Map<String, Object> details) {
        BigDecimal cashAdvances = summary.cashAdvances() == null ? BigDecimal.ZERO : summary.cashAdvances();
        BigDecimal fees = summary.fees() == null ? BigDecimal.ZERO : summary.fees();
        BigDecimal expectedTotalOutstanding = summary.previousBalance()
                .add(summary.purchases())
                .add(cashAdvances)
                .add(fees)
                .subtract(summary.paymentsAndCredits());
        BigDecimal difference = summary.totalOutstanding().subtract(expectedTotalOutstanding);

        details.put("extractionMethod", summary.extractionMethod().name());
        details.put("previousBalance", summary.previousBalance());
        details.put("purchases", summary.purchases());
        details.put("cashAdvances", cashAdvances);
        details.put("fees", fees);
        details.put("paymentsAndCredits", summary.paymentsAndCredits());
        details.put("totalOutstanding", summary.totalOutstanding());
        details.put("expectedTotalOutstanding", expectedTotalOutstanding);
        details.put("difference", difference);

        if (difference.signum() == 0) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }
        if (isWholeRupees(summary.totalOutstanding()) && !isWholeRupees(expectedTotalOutstanding)
                && difference.abs().compareTo(BigDecimal.ONE) < 0) {
            details.put("totalRoundedToRupee", true);
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("explanation", "The previous balance, purchases, cash advances, fees, and "
                + "payments/credits this statement prints about itself do not add up to its own "
                + "printed outstanding balance. This does not implicate any parsed transaction -- no "
                + "transaction row was read to produce this finding -- it means this extraction "
                + "misread one of the statement's own summary figures.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    private static boolean isWholeRupees(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() <= 0;
    }
}
