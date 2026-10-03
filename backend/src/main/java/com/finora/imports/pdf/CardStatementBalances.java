package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;

import java.math.BigDecimal;
import java.util.List;

/**
 * The two card-statement figures a review screen opens with: the total amount due, and the opening
 * balance a new card account starts from.
 */
final class CardStatementBalances {

    private CardStatementBalances() {}

    /**
     * The card's printed previous balance, when this statement's own rows carry it exactly to its
     * total amount due, or to within a rupee of a total printed without paise; otherwise null, and the client works the opening balance
     * backwards from the total due as before.
     *
     * <p>Why the printed figure, when it agrees: a total due printed in whole rupees (a real
     * IndusInd card) puts the backwards estimate off by the dropped paise, where the previous
     * balance is printed exactly. Why only when it agrees: the summary reader keeps no sign, so a
     * previous credit balance ("Cr") reads as money owed, and a misread or a missed row would put
     * the new account's balance wrong by the difference. A rupee or more apart, neither figure is
     * trusted over the other here.
     */
    static BigDecimal openingBalance(CreditCardSummaryEvidence summary, List<StagedRow> rows) {
        if (summary == null || summary.previousBalance() == null || summary.totalAmountDue() == null) return null;
        if (summary.conflictingFields().contains("previousBalance")
                || summary.conflictingFields().contains("totalAmountDue")) return null;
        // Same sign convention as the client's estimate: a charge adds to what is owed, anything
        // else (a payment, a refund) takes from it.
        BigDecimal net = BigDecimal.ZERO;
        if (rows != null) {
            for (StagedRow row : rows) {
                if (row.amount() == null) continue;
                net = "EXPENSE".equals(row.type()) ? net.add(row.amount()) : net.subtract(row.amount());
            }
        }
        BigDecimal gap = summary.totalAmountDue().subtract(net).subtract(summary.previousBalance()).abs();
        if (gap.signum() == 0) return summary.previousBalance();
        // A gap under a rupee is rounding only when the total due was printed without paise --
        // the same allowance CreditCardStatementTotalsValidator makes. A total printed with paise
        // was not rounded, so any gap there means a row is wrong.
        boolean totalIsWholeRupees = summary.totalAmountDue().remainder(BigDecimal.ONE).signum() == 0;
        return totalIsWholeRupees && gap.compareTo(BigDecimal.ONE) < 0 ? summary.previousBalance() : null;
    }

    /**
     * The summary with its total amount due replaced by the payment box's "Total payment due"
     * ({@link UnlabelledCardPaymentSummaryExtractor}), when the box was read. On the real HSBC card
     * the summary table's own headline is "Net Outstanding Balance", which also counts a loan's
     * future instalments; the box holds what this cycle bills. Every other field is kept as
     * printed.
     */
    static CreditCardSummaryEvidence withBoxTotalPaymentDue(CreditCardSummaryEvidence summary, BigDecimal boxTotal) {
        if (boxTotal == null) return summary;
        CreditCardSummaryEvidence s = summary == null ? CreditCardSummaryEvidence.NONE : summary;
        return new CreditCardSummaryEvidence(s.previousBalance(), s.purchases(), s.cashAdvances(), s.fees(),
                s.paymentsAndCredits(), boxTotal, s.extractionMethod(), s.conflictingFields());
    }
}
