package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every figure below is invented. */
class CardStatementBalancesTest {

    private static StagedRow row(String amount, String type) {
        return new StagedRow(LocalDate.of(2026, 3, 10), "SAMPLE", new BigDecimal(amount), type,
                null, null, null, false, null, null, null, null, null, null, null, null, null);
    }

    private static CreditCardSummaryEvidence summary(String previous, String total, List<String> conflicts) {
        return new CreditCardSummaryEvidence(previous == null ? null : new BigDecimal(previous), null, null, null,
                null, total == null ? null : new BigDecimal(total), ExtractionMethod.GRID, conflicts);
    }

    // Purchases 700 + 300, one payment of 500: the rows add 500 to what is owed.
    private static final List<StagedRow> ROWS = List.of(row("700.00", "EXPENSE"), row("300.00", "EXPENSE"),
            row("500.00", "INCOME"));

    // --- Opening balance from the printed previous balance ---

    @Test
    void usesThePrintedPreviousBalance_whenTheRowsCarryItToTheTotalDue() {
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "2000.40", List.of()), ROWS))
                .isEqualByComparingTo("1500.40");
    }

    @Test
    void usesThePrintedPreviousBalance_whenTheTotalDueDropsItsPaise() {
        // The total due is printed in whole rupees; previous balance + rows lands 0.40 short of it.
        // Working backwards from the total would carry that rounding into the opening balance.
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "2001.00", List.of()), ROWS))
                .isEqualByComparingTo("1500.40");
    }

    @Test
    void usesNothing_forASubRupeeGap_whenTheTotalDueKeepsItsPaise() {
        // A total printed with paise was not rounded, so a 0.40 gap is a wrong row, not rounding.
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "2000.80", List.of()), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "2000.41", List.of()), ROWS)).isNull();
    }

    @Test
    void usesNothing_whenTheRowsDoNotCarryThePreviousBalanceToTheTotalDue() {
        // A missed row, a misread figure, or a credit balance read as money owed: a rupee or more out.
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "2001.40", List.of()), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", "1999.40", List.of()), ROWS)).isNull();
    }

    @Test
    void usesNothing_whenEitherFigureIsMissingOrDisputed() {
        assertThat(CardStatementBalances.openingBalance(summary(null, "2000.40", List.of()), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(summary("1500.40", null, List.of()), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(
                summary("1500.40", "2000.40", List.of("previousBalance")), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(
                summary("1500.40", "2000.40", List.of("totalAmountDue")), ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(null, ROWS)).isNull();
        assertThat(CardStatementBalances.openingBalance(CreditCardSummaryEvidence.NONE, ROWS)).isNull();
    }

    @Test
    void aStatementWithNoRows_usesThePreviousBalanceOnlyWhenItIsTheTotalDue() {
        assertThat(CardStatementBalances.openingBalance(summary("800.00", "800.00", List.of()), List.of()))
                .isEqualByComparingTo("800.00");
        assertThat(CardStatementBalances.openingBalance(summary("800.00", "900.00", List.of()), List.of())).isNull();
        assertThat(CardStatementBalances.openingBalance(summary("800.00", "800.00", List.of()), null))
                .isEqualByComparingTo("800.00");
    }

    // --- Total due from the payment box ---

    @Test
    void theBoxsTotalPaymentDue_replacesTheSummarysTotal_keepingEverythingElse() {
        CreditCardSummaryEvidence printed = new CreditCardSummaryEvidence(new BigDecimal("100.00"),
                new BigDecimal("20.00"), null, null, new BigDecimal("5.00"), new BigDecimal("9000.00"),
                ExtractionMethod.INLINE_LABEL_VALUE, List.of());

        CreditCardSummaryEvidence result = CardStatementBalances.withBoxTotalPaymentDue(printed, new BigDecimal("6000.00"));

        assertThat(result.totalAmountDue()).isEqualByComparingTo("6000.00");
        assertThat(result.previousBalance()).isEqualByComparingTo("100.00");
        assertThat(result.purchases()).isEqualByComparingTo("20.00");
        assertThat(result.paymentsAndCredits()).isEqualByComparingTo("5.00");
        assertThat(result.extractionMethod()).isEqualTo(ExtractionMethod.INLINE_LABEL_VALUE);
    }

    @Test
    void withNoBoxTotal_theSummaryIsUnchanged() {
        CreditCardSummaryEvidence printed = summary("100.00", "9000.00", List.of());
        assertThat(CardStatementBalances.withBoxTotalPaymentDue(printed, null)).isSameAs(printed);
    }

    @Test
    void withNoPrintedSummary_theBoxTotalStandsAlone() {
        CreditCardSummaryEvidence result =
                CardStatementBalances.withBoxTotalPaymentDue(CreditCardSummaryEvidence.NONE, new BigDecimal("6000.00"));
        assertThat(result.totalAmountDue()).isEqualByComparingTo("6000.00");
        assertThat(result.previousBalance()).isNull();
        assertThat(result.conflictingFields()).isEmpty();

        assertThat(CardStatementBalances.withBoxTotalPaymentDue(null, new BigDecimal("6000.00")).totalAmountDue())
                .isEqualByComparingTo("6000.00");
    }
}
