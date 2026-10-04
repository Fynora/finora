package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.RowKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OcrBalanceCellRepair}. The misread shapes are the ones measured on a scanned Union Bank of
 * India statement (a lost decimal point, one wrong digit, one extra digit); every value is invented.
 */
class OcrBalanceCellRepairTest {

    private static StagedRow row(String type, String amount, String balance) {
        return new StagedRow(LocalDate.of(2026, 9, 15), "UPI/SAMPLE", new BigDecimal(amount), type,
                "Uncategorized", "default", null, false, null, balance == null ? null : new BigDecimal(balance),
                null, RowKind.TRANSACTION, null, null, null, null, null);
    }

    private static List<String> corrected(StagedRow... rows) {
        return OcrBalanceCellRepair.corrections(Arrays.asList(rows)).stream()
                .map(b -> b == null ? null : b.toPlainString()).toList();
    }

    @Test
    void aLostDecimalPointIsRestoredWhenBothNeighboursAgree() {
        assertThat(corrected(
                row("INCOME", "25.00", "25.12"),
                row("INCOME", "50.00", "7512"),      // printed 75.12
                row("EXPENSE", "75.00", "0.12")))
                .containsExactly(null, "75.12", null);
    }

    @Test
    void oneWrongOrExtraDigitIsRestored() {
        assertThat(corrected(
                row("EXPENSE", "10.00", "565.92"),
                row("EXPENSE", "12.00", "953.92"),   // printed 553.92: a 5 read as 9
                row("EXPENSE", "15.00", "538.92")))
                .containsExactly(null, "553.92", null);
        assertThat(corrected(
                row("INCOME", "100.00", "282.92"),
                row("INCOME", "50.00", "3352.92"),   // printed 332.92: a 3 doubled
                row("EXPENSE", "320.00", "12.92")))
                .containsExactly(null, "332.92", null);
    }

    @Test
    void rowsWhoseBalanceWasNotReadAreCarriedThroughAndLeftUnread() {
        assertThat(corrected(
                row("INCOME", "25.00", "25.12"),
                row("INCOME", "50.00", "7512"),
                row("EXPENSE", "75.00", null),       // printed 0.12, not read
                row("INCOME", "7000.00", "7000.12")))
                .containsExactly(null, "75.12", null, null);
    }

    @Test
    void aBreakTheNextRowDoesNotRejoinIsNeverRepaired() {
        // The next balance disagrees too: a missing row or a misread AMOUNT, not a misread cell.
        assertThat(corrected(
                row("INCOME", "25.00", "25.12"),
                row("INCOME", "50.00", "7512"),
                row("EXPENSE", "75.00", "100.00")))
                .containsOnlyNulls();
    }

    @Test
    void aDifferenceOfMoreThanOneGlyphIsNeverRepaired() {
        assertThat(corrected(
                row("EXPENSE", "10.00", "565.92"),
                row("EXPENSE", "12.00", "999.92"),   // two digits away from 553.92
                row("EXPENSE", "15.00", "538.92")))
                .containsOnlyNulls();
    }

    @Test
    void theFirstAndLastReadableBalancesHaveNoTwoSidedEvidenceAndAreLeftAlone() {
        assertThat(corrected(row("EXPENSE", "10.00", "999.00"), row("EXPENSE", "10.00", "500.00")))
                .containsOnlyNulls();
        assertThat(corrected()).isEmpty();
    }

    @Test
    void oneGlyphApartComparesDigitsNotScale() {
        assertThat(OcrBalanceCellRepair.oneGlyphApart(new BigDecimal("7512"), new BigDecimal("75.12"))).isTrue();
        assertThat(OcrBalanceCellRepair.oneGlyphApart(new BigDecimal("112.59"), new BigDecimal("112.99"))).isTrue();
        assertThat(OcrBalanceCellRepair.oneGlyphApart(new BigDecimal("1000"), new BigDecimal("1000.00"))).isFalse();
        assertThat(OcrBalanceCellRepair.oneGlyphApart(new BigDecimal("-75.12"), new BigDecimal("75.12"))).isFalse();
    }
}
