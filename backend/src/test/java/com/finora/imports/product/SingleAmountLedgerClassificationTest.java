package com.finora.imports.product;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.finora.imports.product.ProductEvidenceCollector.Section;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-11. Two real savings statements classified UNKNOWN while every other savings statement in
 * the corpus reached SAVINGS: neither prints separate debit and credit columns, so
 * DEBIT_CREDIT_COLUMNS never fired. One prints a single amount column whose values carry "(Cr)" or
 * "(Dr)"; the other a single amount column beside a "Type" column holding DR or CR. The second also
 * mentions "installments" in its terms and conditions, which disqualified every account type. Values
 * here are invented; only the layouts are taken from the real documents.
 */
class SingleAmountLedgerClassificationTest {

    private final ProductEvidenceCollector collector = new ProductEvidenceCollector();
    private final FinancialProductClassifier classifier = new FinancialProductClassifier(collector);

    private static Map<String, String> row(String... kv) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Date | Transaction Id | Remarks | Amount | Balance, direction as a suffix on the amount. */
    private static Section suffixedAmountLedger(List<String> text) {
        List<Map<String, String>> rows = List.of(
                row("Date", "01-05-2026", "Transaction Id", "S1", "Remarks", "UPI/SAMPLE A", "Amount( )", "500.00(Cr)", "Balance( )", "1500.00(Cr)"),
                row("Date", "02-05-2026", "Transaction Id", "S2", "Remarks", "UPI/SAMPLE B", "Amount( )", "200.00(Dr)", "Balance( )", "1300.00(Cr)"));
        return new Section(List.of("Date", "Transaction Id", "Remarks", "Amount( )", "Balance( )"),
                text, null, rows.size(), 0, 1, rows);
    }

    /** Date | Instrument ID | Amount(INR) | Type | Balance | Remarks, direction in its own column. */
    private static Section typeColumnLedger(List<String> text) {
        List<Map<String, String>> rows = List.of(
                row("Date", "30/06/2026", "Instrument ID", "", "Amount(INR)", "100.0", "Type", "DR", "Balance", "900.00", "Remarks", "UPI/DR/SAMPLE"),
                row("Date", "01/07/2026", "Instrument ID", "", "Amount(INR)", "300.0", "Type", "CR", "Balance", "1200.00", "Remarks", "NEFT/SAMPLE"));
        return new Section(List.of("Date", "Instrument ID", "Amount(INR)", "Type", "Balance", "Remarks"),
                text, null, rows.size(), 0, 1, rows);
    }

    @Test
    void anAmountColumnMarkedCreditAndDebitRowByRowRecordsBothDirections() {
        SectionEvidence evidence = collector.collect(suffixedAmountLedger(List.of()));

        assertThat(evidence.has(ProductSignal.DEBIT_CREDIT_COLUMNS)).isTrue();
        assertThat(evidence.strongestSourceFor(ProductSignal.DEBIT_CREDIT_COLUMNS)).contains(EvidenceSource.ROW_DATA);
    }

    @Test
    void aTypeColumnHoldingDrAndCrRecordsBothDirections() {
        SectionEvidence evidence = collector.collect(typeColumnLedger(List.of()));

        assertThat(evidence.has(ProductSignal.DEBIT_CREDIT_COLUMNS)).isTrue();
    }

    @Test
    void markersInOneDirectionOnlyAreNotEvidenceOfBoth() {
        // The balance column of the suffixed layout is all "(Cr)" -- an account in credit. That says
        // nothing about money moving both ways.
        List<Map<String, String>> rows = List.of(
                row("Date", "01-05-2026", "Remarks", "SAMPLE", "Balance", "1500.00(Cr)"),
                row("Date", "02-05-2026", "Remarks", "SAMPLE", "Balance", "1300.00(Cr)"));
        SectionEvidence evidence = collector.collect(new Section(List.of("Date", "Remarks", "Balance"),
                List.of(), null, rows.size(), 0, 1, rows));

        assertThat(evidence.has(ProductSignal.DEBIT_CREDIT_COLUMNS)).isFalse();
    }

    @Test
    void narrationEndingInTheLettersDrOrCrIsNotAMarker() {
        List<Map<String, String>> rows = List.of(
                row("Date", "01-05-2026", "Remarks", "SAMPLE HDR", "Amount", "500.00"),
                row("Date", "02-05-2026", "Remarks", "SAMPLE MCR", "Amount", "200.00"));
        SectionEvidence evidence = collector.collect(new Section(List.of("Date", "Remarks", "Amount"),
                List.of(), null, rows.size(), 0, 1, rows));

        assertThat(evidence.has(ProductSignal.DEBIT_CREDIT_COLUMNS)).isFalse();
    }

    @Test
    void aSuffixedAmountSavingsLedgerThatNamesItselfIsSavings() {
        var result = classifier.classify(suffixedAmountLedger(List.of("Account Type Savings Account")));

        assertThat(result.type()).isEqualTo(FinancialProductType.SAVINGS);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    void aTypeColumnLedgerWhoseTermsMentionInstallmentsIsStillSavings() {
        var result = classifier.classify(typeColumnLedger(List.of(
                "Charges may be levied on installments on the rates prescribed by bank from time to time")));

        assertThat(result.type()).isEqualTo(FinancialProductType.SAVINGS);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    void anInstallmentScheduleIsStillARecurringDeposit() {
        // No narration column: a schedule, not a ledger, so the prose exemption must not apply and
        // SAVINGS must stay disqualified by its installment field.
        var result = classifier.classify(Section.of(
                List.of("Number", "Due Date", "Amount Paid", "Due", "Status", "Running balance"),
                List.of("Installment Frequency Monthly", "Rate of Interest", "Maturity Date"), 12));

        assertThat(result.type()).isEqualTo(FinancialProductType.RECURRING_DEPOSIT);
    }

    @Test
    void anInstallmentColumnStillDisqualifiesSavings() {
        // The exemption is for prose. A real column named for installments is structure.
        List<Map<String, String>> rows = List.of(
                row("Date", "01-05-2026", "Remarks", "SAMPLE", "Installment", "500.00(Cr)", "Balance", "1500.00"),
                row("Date", "02-05-2026", "Remarks", "SAMPLE", "Installment", "200.00(Dr)", "Balance", "1300.00"));
        var result = classifier.classify(new Section(List.of("Date", "Remarks", "Installment", "Balance"),
                List.of(), null, rows.size(), 0, 1, rows));

        assertThat(result.type()).isNotEqualTo(FinancialProductType.SAVINGS);
    }
}
