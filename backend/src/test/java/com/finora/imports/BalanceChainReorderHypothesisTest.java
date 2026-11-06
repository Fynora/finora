package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether re-ordering the disagreeing rows would make the chain hold -- see
 * docs/superpowers/specs/2026-11-06-balance-chain-reorder-hypothesis-design.md.
 *
 * <p>Every statement here is built in the test. The shape being tested is two neighbouring same-day
 * rows listed in the opposite order to the one the bank processed them in, each still carrying its
 * own printed balance. With a balance of 950 before them, a credit Y of 200 and a debit X of 50,
 * the bank's order is Y (1150) then X (1100); the staged order is X (1100) then Y (1150), and the
 * walk reports a gap of +200 on X, -150 on Y and, on the row after, the debit X's amount again.
 */
class BalanceChainReorderHypothesisTest {

    private final BalanceChainValidator validator = new BalanceChainValidator();

    private static StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(LocalDate.parse(date), description, amount == null ? null : new BigDecimal(amount),
                type, "Other", "default", null, false, null,
                balanceAfter == null ? null : new BigDecimal(balanceAfter));
    }

    /** A credit, then five clean debits of 10: the balance before the rows under test is 950. */
    private static List<StagedRow> openingRun() {
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("2026-07-10", "OPENING CREDIT", "1000.00", "INCOME", "1000.00"));
        int balance = 1000;
        for (int day = 11; day <= 15; day++) {
            balance -= 10;
            rows.add(row("2026-07-" + day, "ROUTINE " + day, "10.00", "EXPENSE", balance + ".00"));
        }
        return rows;
    }

    private static List<StagedRow> cleanTail(int from, int balanceBefore) {
        List<StagedRow> rows = new ArrayList<>();
        int balance = balanceBefore;
        for (int day = from; day < from + 3; day++) {
            balance -= 10;
            rows.add(row("2026-07-" + day, "TAIL " + day, "10.00", "EXPENSE", balance + ".00"));
        }
        return rows;
    }

    /** X and Y transposed, with Z after them, all on the 16th, and a clean tail. Rows 6, 7, 8. */
    private static List<StagedRow> transposedPairStatement() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        rows.addAll(cleanTail(17, 1090));
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hypothesisOf(List<StagedRow> rows, int[] displayedPositionOf) {
        var finding = validator.report(rows, null, displayedPositionOf).findings().get(0);
        return (Map<String, Object>) finding.details().get("reorderHypothesis");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windows(Map<String, Object> hypothesis) {
        return (List<Map<String, Object>>) hypothesis.get("windows");
    }

    @Test
    void aTransposedSameDayPairIsFoundAsTheOnlyOrderThatReconciles() {
        var rows = transposedPairStatement();
        var finding = validator.report(rows, null).findings().get(0);
        assertThat(finding.outcome()).as("the fixture has to disagree, and not be systematic").isEqualTo("WARNING");

        var hypothesis = hypothesisOf(rows, null);

        assertThat(hypothesis).containsEntry("tested", true);
        assertThat(windows(hypothesis)).hasSize(1);
        var window = windows(hypothesis).get(0);
        // The three rows the walk disagreed on, by the position the client shows them at.
        assertThat(window).containsEntry("rows", List.of(6, 7, 8))
                .containsEntry("tested", true)
                .containsEntry("reconciles", true)
                .containsEntry("orderings", 1)
                // Y before X, with Z where it was.
                .containsEntry("order", List.of(7, 6, 8));
    }

    @Test
    void theVerdictAndTheDiscrepanciesAreUntouchedByTheTest() {
        var rows = transposedPairStatement();
        var details = validator.report(rows, null).findings().get(0).details();

        assertThat(validator.report(rows, null).findings().get(0).outcome()).isEqualTo("WARNING");
        assertThat((List<?>) details.get("discrepancies")).hasSize(3);
        assertThat(details).containsEntry("gapsCancel", true);
    }

    @Test
    void balancesSwappedBetweenRowsIsNotAReorder() {
        // The amounts sit where they were; it is the printed balances that traded places. Putting
        // the rows in the other order cannot make that reconcile.
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1150.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1100.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        rows.addAll(cleanTail(17, 1090));

        var hypothesis = hypothesisOf(rows, null);

        assertThat(windows(hypothesis)).hasSize(1);
        assertThat(windows(hypothesis).get(0)).containsEntry("rows", List.of(6, 7))
                .containsEntry("tested", true)
                .containsEntry("reconciles", false)
                .containsEntry("orderings", 0)
                .doesNotContainKey("order");
    }

    @Test
    void oneMisreadAmountIsAWindowOfOneAndReconcilesNoWay() {
        List<StagedRow> rows = new ArrayList<>(openingRun());
        // Debit of 10 read as 0.00: one row off, then the chain carries on from the printed balance.
        rows.set(3, row("2026-07-13", "ROUTINE 13", "0.00", "EXPENSE", "970.00"));
        rows.addAll(cleanTail(16, 950));

        var hypothesis = hypothesisOf(rows, null);

        assertThat(windows(hypothesis)).hasSize(1);
        assertThat(windows(hypothesis).get(0)).containsEntry("rows", List.of(3))
                .containsEntry("tested", true).containsEntry("reconciles", false);
    }

    @Test
    void rowsOnDifferentDaysAreNotTried() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-17", "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row("2026-07-17", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        rows.addAll(cleanTail(18, 1090));

        var hypothesis = hypothesisOf(rows, null);

        assertThat(hypothesis).containsEntry("tested", false);
        assertThat(windows(hypothesis).get(0)).containsEntry("tested", false)
                .containsEntry("reason", "NOT_TESTED_DIFFERENT_DAYS").doesNotContainKey("order");
    }

    @Test
    void aRunLongerThanTheBoundIsNotTried() {
        // Seven same-day debits whose printed balance does not move: seven consecutive gaps of 1.
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("2026-06-01", "OPENING CREDIT", "1000.00", "INCOME", "1000.00"));
        int balance = 1000;
        for (int day = 2; day <= 11; day++) {
            balance -= 10;
            rows.add(row("2026-06-" + String.format("%02d", day), "ROUTINE " + day, "10.00", "EXPENSE", balance + ".00"));
        }
        for (int i = 0; i < BalanceChainValidator.MAX_REORDER_WINDOW_ROWS + 1; i++) {
            rows.add(row("2026-06-12", "STUCK " + i, "1.00", "EXPENSE", balance + ".00"));
        }
        int tail = balance;
        for (int day = 13; day <= 17; day++) {
            tail -= 10;
            rows.add(row("2026-06-" + day, "TAIL " + day, "10.00", "EXPENSE", tail + ".00"));
        }
        assertThat(validator.report(rows, null).findings().get(0).outcome()).isEqualTo("WARNING");

        var hypothesis = hypothesisOf(rows, null);

        assertThat(hypothesis).containsEntry("tested", false);
        assertThat(windows(hypothesis).get(0)).containsEntry("reason", "NOT_TESTED_TOO_MANY_ROWS")
                .containsEntry("tested", false);
    }

    @Test
    void aSystematicFailureIsNotTried() {
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("2026-07-10", "OPENING CREDIT", "1000.00", "INCOME", "1000.00"));
        for (int day = 11; day <= 14; day++) {
            rows.add(row("2026-07-" + day, "STUCK " + day, "10.00", "EXPENSE", "1000.00"));
        }
        assertThat(validator.report(rows, null).findings().get(0).outcome()).isEqualTo("FAILED");

        var hypothesis = hypothesisOf(rows, null);

        assertThat(hypothesis).containsEntry("tested", false)
                .containsEntry("reason", "NOT_TESTED_FAILED_VERDICT").doesNotContainKey("windows");
    }

    @Test
    void aTransposedPairAtTheEndOfTheStatementIsFoundWithNothingAfterIt() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1150.00"));

        var hypothesis = hypothesisOf(rows, null);

        assertThat(windows(hypothesis).get(0)).containsEntry("rows", List.of(6, 7))
                .containsEntry("reconciles", true).containsEntry("order", List.of(7, 6));
    }

    @Test
    void aNewestFirstStatementNamesTheRowsWhereTheClientShowsThem() {
        // The document is printed newest-first, so the walk runs it backwards. The positions must be
        // the ones in the list as given, not along the walk.
        var chainOrder = transposedPairStatement();
        List<StagedRow> newestFirst = new ArrayList<>(chainOrder);
        Collections.reverse(newestFirst);
        int last = chainOrder.size() - 1;

        var hypothesis = hypothesisOf(newestFirst, null);

        assertThat(windows(hypothesis)).hasSize(1);
        assertThat(windows(hypothesis).get(0))
                .containsEntry("rows", List.of(last - 8, last - 7, last - 6))
                // Walk rows 7, 6, 8 (Y, X, Z) sit at last-7, last-6, last-8 in the list as given.
                .containsEntry("order", List.of(last - 7, last - 6, last - 8))
                .containsEntry("reconciles", true);
    }

    @Test
    void aDisplayedPositionMappingIsHonouredForTheRowsAndTheOrder() {
        var rows = transposedPairStatement();
        // The client shows the rows under test three places further down, and the rows that were
        // there in their place: positions 6,7,8 are shown at 9,10,11 and 9,10,11 at 6,7,8.
        int[] displayedPositionOf = new int[rows.size()];
        for (int i = 0; i < displayedPositionOf.length; i++) displayedPositionOf[i] = i;
        for (int i = 6; i <= 8; i++) {
            displayedPositionOf[i] = i + 3;
            displayedPositionOf[i + 3] = i;
        }

        var hypothesis = hypothesisOf(rows, displayedPositionOf);

        assertThat(windows(hypothesis).get(0)).containsEntry("rows", List.of(9, 10, 11))
                .containsEntry("order", List.of(10, 9, 11));
    }

    @Test
    void nothingButPositionsAndFlagsLeavesTheValidator() throws Exception {
        var hypothesis = hypothesisOf(transposedPairStatement(), null);

        String json = new ObjectMapper().writeValueAsString(hypothesis);

        // None of the statement's money, narration or dates, under any name.
        assertThat(json).doesNotContain("1150").doesNotContain("1100").doesNotContain("1090")
                .doesNotContain("950").doesNotContain("200").doesNotContain("X DEBIT")
                .doesNotContain("Y CREDIT").doesNotContain("2026");
        assertThat(json).doesNotContain("expectedBalance").doesNotContain("actualBalance");
    }

    @Test
    void aLongStatementStaysCheap() {
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("2024-01-01", "OPENING CREDIT", "100000.00", "INCOME", "100000.00"));
        long balance = 100000;
        LocalDate day = LocalDate.parse("2024-01-02");
        for (int i = 0; i < 1700; i++) {
            balance -= 10;
            rows.add(row(day.plusDays(i / 3).toString(), "ROW " + i, "10.00", "EXPENSE", balance + ".00"));
        }
        // Two same-day neighbours, one a credit, listed in the opposite order to their balances.
        rows.set(900, row(rows.get(900).date().toString(), "CREDIT", "500.00", "INCOME", rows.get(900).balanceAfter().toPlainString()));
        long before = System.nanoTime();
        var hypothesis = hypothesisOf(rows, null);
        long millis = (System.nanoTime() - before) / 1_000_000;

        assertThat(hypothesis).isNotNull();
        // Not a benchmark: a generous ceiling that a pass over all 1700 rows per ordering would break.
        assertThat(millis).isLessThan(5_000);
    }
}
