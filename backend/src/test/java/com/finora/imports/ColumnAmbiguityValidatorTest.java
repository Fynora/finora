package com.finora.imports;

import com.finora.dto.ImportDto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reporting rows the document did not settle.
 *
 * <p>The line these tests defend is the one between "ambiguous" and "merely unusual". A
 * separate-columns layout printing 0.00 in the side that did not move is the NORMAL case on most
 * Indian statements — flagging it would put a warning on nearly every row of nearly every import
 * and teach people to ignore the panel, which costs more than the rule earns.
 */
class ColumnAmbiguityValidatorTest {

    private final ColumnAmbiguityValidator validator = new ColumnAmbiguityValidator();

    private static Map<String, String> row(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    @Test
    void flagsACellHoldingTwoAmounts() {
        // The exact shape that made a 25,000 deposit read as an expense: whichever value the parser
        // picked, the other had an equal claim, and nothing downstream could tell afterwards.
        var finding = validator.check(List.of(
                row("Txn Date", "10/07/2026", "Narration", "UPI CREDIT", "Deposits", "0.00 25,000.00")));

        assertThat(finding.rule()).isEqualTo("COLUMN_AMBIGUITY");
        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry("ambiguousRows", 1);
        assertThat(finding.details().get("ambiguities").toString())
                .contains("MULTIPLE_AMOUNTS_IN_ONE_COLUMN")
                .contains("Deposits");
    }

    /** A real scanned HSBC statement: its footer ("<total>DR As at <OCR noise> Balance Carried
     *  Forward") carries an OCR'd date and several numbers in the withdrawals cell. Nothing was read
     *  from it -- the row could not be parsed and was set aside -- so no reading was chosen. */
    @Test
    void ignoresARowThatCouldNotBeParsedAtAll() {
        Map<String, String> footer = row("Date", "05JUN2026", "Details", "Withdrawals Deposits",
                "Withdrawals", "1,000.00DR As at 10.000.00 Balance Carried Forward", "Balance", "29JUN2026 500.00");
        var finding = validator.check(List.of(footer),
                List.of(new ImportDto.UnparseableRow(footer, "Amount value didn't match any known numeric format")));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("ambiguousRows", 0);
    }

    @Test
    void stillFlagsTheSameShapeWhenTheRowWasStaged() {
        Map<String, String> staged = row("Txn Date", "10/07/2026", "Narration", "UPI CREDIT", "Deposits", "0.00 25,000.00");
        Map<String, String> other = row("Txn Date", "11/07/2026", "Narration", "SAMPLE NOTE", "Deposits", "abc");
        var finding = validator.check(List.of(staged, other),
                List.of(new ImportDto.UnparseableRow(other, "no amount")));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry("ambiguousRows", 1);
    }

    @Test
    void flagsARowWhereBothDirectionsClaimTheMoney() {
        var finding = validator.check(List.of(
                row("Txn Date", "10/07/2026", "Narration", "SOMETHING", "Withdrawals", "436.00", "Deposits", "25,000.00")));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("ambiguities").toString())
                .contains("BOTH_DIRECTIONS_HAVE_A_VALUE");
    }

    @Test
    void acceptsTheOrdinaryLayoutThatPrintsZeroInTheUnusedColumn() {
        // Most Indian statements do exactly this. Treating it as ambiguous would warn on almost
        // every row of almost every import, which is how a verification panel becomes wallpaper.
        var finding = validator.check(List.of(
                row("Txn Date", "16/07/2026", "Narration", "PREMIUM", "Withdrawals", "20.00", "Deposits", "0.00"),
                row("Txn Date", "10/07/2026", "Narration", "UPI CREDIT", "Withdrawals", "0.00", "Deposits", "25,000.00")));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("ambiguousRows", 0);
    }

    @Test
    void ignoresMultipleNumbersInColumnsThatAreNotAmounts() {
        // A narration routinely carries several numbers -- a reference, a date, a UPI id. Only the
        // columns that decide how much and which way can be ambiguous about how much and which way.
        var finding = validator.check(List.of(
                row("Txn Date", "10/07/2026", "Narration", "999999999-UPI-999999999999 Ref 9999999999999999",
                    "Withdrawals", "20.00", "Deposits", "0.00")));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void reportsWhichRowSoItCanBeFound() {
        var finding = validator.check(List.of(
                row("Txn Date", "16/07/2026", "Withdrawals", "20.00", "Deposits", "0.00"),
                row("Txn Date", "10/07/2026", "Withdrawals", "0.00", "Deposits", "0.00 25,000.00")));

        assertThat(finding.details().get("ambiguities").toString()).contains("rowIndex=1");
        assertThat(finding.details()).containsEntry("rowsChecked", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void namesARowByWhereItIsShownNotWhereItWasLocated() {
        // Located newest first behind a line that never stages; shown oldest first. The first
        // located transaction is the last one shown, and the list is given in the order shown.
        var finding = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Narration", "OPENING BALANCE", "Balance", "10,000.00"),
                row("Txn Date", "03/07/2026", "Withdrawals", "20.00", "Deposits", "5.00"),
                row("Txn Date", "02/07/2026", "Withdrawals", "30.00", "Deposits", "0.00"),
                row("Txn Date", "01/07/2026", "Withdrawals", "40.00", "Deposits", "0.00 7.00")),
                List.of(), new int[] {ColumnAmbiguityValidator.NOT_SHOWN, 2, 1, 0});

        var ambiguities = (List<Map<String, Object>>) finding.details().get("ambiguities");
        assertThat(ambiguities).extracting(a -> a.get("rowIndex")).containsExactly(0, 2);
        assertThat(ambiguities).extracting(a -> a.get("kind"))
                .containsExactly("MULTIPLE_AMOUNTS_IN_ONE_COLUMN", "BOTH_DIRECTIONS_HAVE_A_VALUE");
        assertThat(finding.details()).containsEntry("rowsChecked", 4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsARowThatIsNotShownWithoutGivingItANumber() {
        // Still reported: the document did not settle it, and dropping it would turn a warning into
        // a pass on the strength of a row going missing. But the client prints the number as "Row N"
        // beside the rows it shows, so any number here would name one of those instead.
        var finding = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "5.00"),
                row("Txn Date", "02/07/2026", "Withdrawals", "30.00", "Deposits", "6.00")),
                List.of(), new int[] {ColumnAmbiguityValidator.NOT_SHOWN, 0});

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry("ambiguousRows", 2);
        var ambiguities = (List<Map<String, Object>>) finding.details().get("ambiguities");
        assertThat(ambiguities).hasSize(2);
        assertThat(ambiguities.get(0)).containsEntry("rowIndex", 0).containsEntry("value", "6.00 / 30.00");
        assertThat(ambiguities.get(1)).doesNotContainKey("rowIndex").containsEntry("value", "5.00 / 20.00");
    }

    @Test
    void refusesAMappingThatDoesNotDescribeTheLocatedRows() {
        List<Map<String, String>> located = List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "0.00"),
                row("Txn Date", "02/07/2026", "Withdrawals", "30.00", "Deposits", "0.00"));

        // Refused even though nothing here is ambiguous: a wrong mapping that only surfaced on the
        // statement with a finding would be found by a customer.
        assertThatThrownBy(() -> validator.check(located, List.of(), new int[] {0}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 row(s) but 2 were located");
        assertThatThrownBy(() -> validator.check(located, List.of(), new int[] {0, 0}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("used twice");
        assertThatThrownBy(() -> validator.check(located, List.of(), new int[] {0, 2}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> validator.check(located, List.of(), new int[] {0, -2}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> validator.check(null, List.of(), new int[] {0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saysWhichListItsNumbersCountAlong() {
        List<Map<String, String>> located = List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "5.00"));
        var finding = validator.check(located, List.of(), new int[] {0});

        assertThat(finding.details())
                .containsEntry(ColumnAmbiguityValidator.ROW_INDEX_BASIS, ColumnAmbiguityValidator.ROWS_SHOWN);
        // Without a mapping the numbers count the located rows, so the marker is not claimed -- and
        // such a report, once stored, is converted on read like any other located-row report.
        var unmapped = validator.check(located);
        assertThat(unmapped.details()).doesNotContainKey(ColumnAmbiguityValidator.ROW_INDEX_BASIS);
        assertThat(ColumnAmbiguityValidator.renumberStored(unmapped, List.of(
                new ImportDto.StagedRow(java.time.LocalDate.of(2026, 7, 1), "A", new java.math.BigDecimal("1.00"),
                        "EXPENSE", "Other", "rule", null, false, null, null).withRowPosition(1)))
                .details()).containsEntry(ColumnAmbiguityValidator.ROW_INDEX_BASIS, ColumnAmbiguityValidator.ROWS_SHOWN);
        // And the marker stays out of the evidence table: it is a fact about our numbering, not
        // a count, and the recorder keeps only the keys it names.
        assertThat(com.finora.imports.analysis.ImportVerificationRecorder.structuralDetailsOf(finding.details()))
                .doesNotContainKey(ColumnAmbiguityValidator.ROW_INDEX_BASIS)
                .containsEntry("ambiguitiesCount", 1);
    }

    @Test
    void leavesEverythingButAnOldAmbiguityListAsItCame() {
        var balance = new ImportDto.VerificationFinding("BALANCE_CHAIN", "WARNING", Map.of("rowsChecked", 3));
        var clean = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "0.00")));
        var fresh = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "5.00")), List.of(), new int[] {0});
        List<ImportDto.StagedRow> shown = List.of();

        assertThat(ColumnAmbiguityValidator.renumberStored(balance, shown)).isSameAs(balance);
        assertThat(ColumnAmbiguityValidator.renumberStored(clean, shown)).isSameAs(clean);
        assertThat(ColumnAmbiguityValidator.renumberStored(fresh, shown)).isSameAs(fresh);
        assertThat(ColumnAmbiguityValidator.renumberStored(null, shown)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOldNumberWhoseRowCarriesNoPositionLosesItsNumberRatherThanKeepingAWrongOne() {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("rowIndex", 0);
        old.put("kind", "BOTH_DIRECTIONS_HAVE_A_VALUE");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ambiguities", List.of(old));
        var stored = new ImportDto.VerificationFinding(ColumnAmbiguityValidator.RULE, "WARNING", details);
        var unnumbered = new ImportDto.StagedRow(java.time.LocalDate.of(2026, 7, 1), "A",
                new java.math.BigDecimal("1.00"), "EXPENSE", "Other", "rule", null, false, null, null);

        var served = ColumnAmbiguityValidator.renumberStored(stored, List.of(unnumbered));

        var ambiguities = (List<Map<String, Object>>) served.details().get("ambiguities");
        assertThat(ambiguities.get(0)).doesNotContainKey("rowIndex").containsEntry("kind", "BOTH_DIRECTIONS_HAVE_A_VALUE");
        assertThat(served.outcome()).isEqualTo("WARNING");
        assertThat(old).as("the stored map itself is not changed").containsEntry("rowIndex", 0);
    }

    @Test
    void acceptsAMappingInWhichNoLocatedRowIsShown() {
        var finding = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Withdrawals", "20.00", "Deposits", "0.00")),
                List.of(), new int[] {ColumnAmbiguityValidator.NOT_SHOWN});

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        var noRows = validator.check(List.of(), List.of(), new int[0]);
        assertThat(noRows.outcome()).isEqualTo("NOT_APPLICABLE");
        // The rows were handed over; there were none. "Not available" would be untrue.
        assertThat(noRows.details().get("reason")).isEqualTo("The statement had no rows to check for ambiguity.");
        assertThat(validator.check(List.of()).details().get("reason").toString()).contains("not available");
    }

    @Test
    void staysAWarningEvenWhenEveryRowIsAmbiguous() {
        // Escalating on volume would imply a view about which reading is right. This rule has none
        // -- it reports that the document did not say, and most guesses it flags will be correct.
        var finding = validator.check(List.of(
                row("Txn Date", "01/07/2026", "Deposits", "0.00 100.00"),
                row("Txn Date", "02/07/2026", "Deposits", "0.00 200.00"),
                row("Txn Date", "03/07/2026", "Deposits", "0.00 300.00")));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry("ambiguousRows", 3);
    }

    @Test
    void ignoresTrailingBoilerplateThatIsNotATransaction() {
        // Found by running the diagnostic against a statement that parses perfectly and watching
        // this rule warn anyway. A statement's summary block lands in whichever column its x
        // position falls under -- here several amounts in a direction column -- and flagging it
        // put a warning on a flawless import, which is the exact false accusation this framework
        // is built to avoid.
        var finding = validator.check(List.of(
                row("Txn Date", "10/07/2026", "Narration", "UPI CREDIT",
                    "Withdrawals", "0.00", "Deposits", "25,000.00"),
                row("Txn Date", "Total Withdrawal Balance***", "Narration", "Opening Balance Debit Amount",
                    "Withdrawals", "Credit Amount 538.00 25,000.00 Credit Count 3 1")));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("ambiguousRows", 0);
    }

    @Test
    void reportsNotApplicableWhenTheOriginalColumnsWereNotAvailable() {
        assertThat(validator.check(List.of()).outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(validator.check(null).outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
