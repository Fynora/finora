package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header check used to be blind to a statement that prints each transaction as a date line
 * followed by a separate line holding the narration and the amounts, under a header printed on three
 * stacked lines. {@code rowsCleanlyExplainedBy} counts a row only when it carries a date, so the
 * amount lines could never count, the check sat exactly on its "weak" boundary, and the correct
 * seven-column header was never adopted. Every amount then landed inside the last text column.
 *
 * <p>Every value below is hand-synthesized (Synthetic Fixture Policy). Only the SHAPE of the real
 * failure is kept: three header lines, a date line, and an amounts line printed more than
 * {@code ROW_Y_TOLERANCE} below it.
 */
class AmountOnlyLinesHeaderRescueTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static List<PositionedText> threeLineHeaderStatement(int transactions) {
        List<PositionedText> r = new ArrayList<>();
        r.add(run("Date(Value", 70f, 50f, 100.0f));
        r.add(run("Ref No.", 290f, 40f, 97.7f));
        r.add(run("Transaction", 340f, 50f, 100.0f));
        r.add(run("Particulars", 170f, 43f, 103.3f));
        r.add(run("Debit(", 400f, 26f, 103.3f));
        r.add(run("Rs)", 426f, 14f, 103.3f));
        r.add(run("Credit(Rs)", 456f, 42f, 103.3f));
        r.add(run("Balance(Rs)", 507f, 49f, 103.3f));
        r.add(run("Date)", 63f, 22f, 108.8f));
        r.add(run("/Cheque No", 275f, 48f, 108.8f));
        r.add(run("Type", 346f, 21f, 108.8f));
        float y = 130f;
        int balance = 5000;
        for (int i = 0; i < transactions; i++) {
            r.add(run("0" + (1 + i) + "-Jan-26", 45f, 40f, y));
            float amountsY = y + 4.0f;
            r.add(run("UPI payment " + i, 112f, 80f, amountsY + 0.04f));
            r.add(run("REF" + (1000 + i), 278f, 41f, amountsY));
            r.add(run("Transfer", 344f, 30f, amountsY));
            String amount = (100 + i) + ".00";
            if (i % 2 == 0) {
                r.add(run(amount, 408f, 32f, amountsY));
                r.add(run("-", 496f, 3f, amountsY));
                balance -= 100 + i;
            } else {
                r.add(run("-", 437f, 3f, amountsY));
                r.add(run(amount, 466f, 32f, amountsY));
                balance += 100 + i;
            }
            r.add(run(balance + ".00", 522f, 36f, amountsY));
            y += 23f;
        }
        return r;
    }

    private static List<Map<String, String>> rowsOf(List<PositionedText> positioned) {
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);
        assertThat(doc.sections()).hasSize(1);
        return doc.sections().get(0).rows();
    }

    private static String cell(Map<String, String> row, String columnPrefix) {
        return row.entrySet().stream()
                .filter(e -> e.getKey().startsWith(columnPrefix))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void threeLineHeaderAndTwoLineRows_keepDebitCreditAndBalanceColumns() {
        List<Map<String, String>> rows = rowsOf(threeLineHeaderStatement(6));

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).keySet()).anyMatch(k -> k.startsWith("Debit"));
        assertThat(rows.get(0).keySet()).anyMatch(k -> k.startsWith("Credit"));
        assertThat(rows.get(0).keySet()).anyMatch(k -> k.startsWith("Balance"));
        // Row 0 is a debit of 100.00 leaving 4900.00; row 1 a credit of 101.00 leaving 5001.00.
        assertThat(cell(rows.get(0), "Debit")).isEqualTo("100.00");
        assertThat(cell(rows.get(0), "Credit")).isEqualTo("-");
        assertThat(cell(rows.get(0), "Balance")).isEqualTo("4900.00");
        assertThat(cell(rows.get(1), "Debit")).isEqualTo("-");
        assertThat(cell(rows.get(1), "Credit")).isEqualTo("101.00");
        assertThat(cell(rows.get(1), "Balance")).isEqualTo("5001.00");
        // The amount no longer rides inside the last text column.
        assertThat(cell(rows.get(0), "Type")).isEqualTo("Transfer");
    }

    @Test
    void everyRowKeepsItsOwnAmounts_notOnlyTheFirst() {
        List<Map<String, String>> rows = rowsOf(threeLineHeaderStatement(6));

        for (int i = 0; i < rows.size(); i++) {
            String debit = cell(rows.get(i), "Debit");
            String credit = cell(rows.get(i), "Credit");
            assertThat(debit).as("debit of row %d", i).isNotNull();
            assertThat(credit).as("credit of row %d", i).isNotNull();
            // Exactly one side carries the amount, the other the "-" placeholder.
            assertThat(debit.equals("-") ^ credit.equals("-")).as("one side only, row %d", i).isTrue();
            assertThat(cell(rows.get(i), "Balance")).as("balance of row %d", i).matches("\\d+\\.00");
        }
    }

    @Test
    void twoTransactions_isTheSmallestTableTheRescueSees() {
        List<Map<String, String>> rows = rowsOf(threeLineHeaderStatement(2));

        assertThat(rows).hasSize(2);
        assertThat(cell(rows.get(0), "Debit")).isEqualTo("100.00");
        assertThat(cell(rows.get(1), "Credit")).isEqualTo("101.00");
    }

    /**
     * Pins the evidence threshold on purpose: one undated colliding line is not enough to rescue a
     * header (HEADER_QUALITY_MIN_SAMPLE). A table of a single transaction therefore keeps the old
     * result. If this threshold is ever lowered deliberately, this is the test to change.
     */
    @Test
    void singleTransaction_isBelowTheEvidenceThreshold_andKeepsTheOldReading() {
        List<Map<String, String>> rows = rowsOf(threeLineHeaderStatement(1));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keySet()).noneMatch(k -> k.startsWith("Debit"));
    }

    @Test
    void ordinaryLedgerWithNarrationContinuationLines_isReadExactlyAsBefore() {
        List<PositionedText> r = new ArrayList<>();
        r.add(run("Date", 70f, 30f, 100f));
        r.add(run("Description", 200f, 70f, 100f));
        r.add(run("Debit", 380f, 40f, 100f));
        r.add(run("Credit", 460f, 40f, 100f));
        r.add(run("Balance", 540f, 50f, 100f));
        float y = 125f;
        int balance = 9000;
        for (int i = 0; i < 8; i++) {
            r.add(run("0" + (1 + i) + "-Feb-26", 71f, 42f, y));
            r.add(run("Card purchase " + i, 201f, 80f, y));
            r.add(run((50 + i) + ".00", 381f, 40f, y));
            balance -= 50 + i;
            r.add(run(balance + ".00", 541f, 40f, y));
            r.add(run("continued narration " + i, 201f, 90f, y + 10f));
            y += 30f;
        }

        List<Map<String, String>> rows = rowsOf(r);

        assertThat(rows).hasSize(8);
        assertThat(rows.get(0).keySet()).containsExactlyInAnyOrder("Date", "Description", "Debit", "Balance");
        assertThat(rows.get(0)).containsEntry("Debit", "50.00").containsEntry("Balance", "8950.00");
        assertThat(rows.get(0).get("Description")).isEqualTo("Card purchase 0 continued narration 0");
    }
}
