package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.TextSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sameDayOrderOnly}: set on a BALANCE_CHAIN warning only when every disagreeing row is
 * explained by same-day rows listed out of the bank's order -- see
 * docs/superpowers/specs/2026-11-09-same-day-order-calm-warning-design.md. Every statement is built
 * here, in the shape {@link BalanceChainReorderHypothesisTest} documents: with 950 before them, the
 * bank applied a credit of 200 (1150) then a debit of 50 (1100), and the statement lists the debit first.
 */
class BalanceChainSameDayOrderOnlyTest {

    private final BalanceChainValidator validator = new BalanceChainValidator();

    private static StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(LocalDate.parse(date), description, new BigDecimal(amount),
                type, "Other", "default", null, false, null, new BigDecimal(balanceAfter));
    }

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

    private static void addTail(List<StagedRow> rows, int from, int balanceBefore) {
        int balance = balanceBefore;
        for (int day = from; day < from + 3; day++) {
            balance -= 10;
            rows.add(row("2026-07-" + day, "TAIL " + day, "10.00", "EXPENSE", balance + ".00"));
        }
    }

    private static List<StagedRow> transposedPair(String dayOfX, String dayOfY) {
        List<StagedRow> rows = openingRun();
        rows.add(row(dayOfX, "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row(dayOfY, "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row(dayOfY, "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        addTail(rows, 17, 1090);
        return rows;
    }

    private VerificationFinding findingOf(List<StagedRow> rows) {
        return validator.report(rows, null).findings().get(0);
    }

    @Test
    void aTransposedSameDayPair_isSameDayOrderOnly_andStaysAWarning() {
        VerificationFinding finding = findingOf(transposedPair("2026-07-16", "2026-07-16"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry(BalanceChainValidator.SAME_DAY_ORDER_ONLY, true);
        assertThat((List<?>) finding.details().get("discrepancies")).as("the facts are all still there").hasSize(3);
    }

    @Test
    void twoTransposedPairsOnDifferentDays_areStillSameDayOrderOnly() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        rows.add(row("2026-07-17", "ROUTINE 17", "10.00", "EXPENSE", "1080.00"));
        rows.add(row("2026-07-17", "ROUTINE 17B", "10.00", "EXPENSE", "1070.00"));
        // Second pair, from 1070: the bank applied a credit of 300 (1370) then a debit of 20 (1350).
        rows.add(row("2026-07-20", "P DEBIT", "20.00", "EXPENSE", "1350.00"));
        rows.add(row("2026-07-20", "Q CREDIT", "300.00", "INCOME", "1370.00"));
        rows.add(row("2026-07-20", "R DEBIT", "10.00", "EXPENSE", "1340.00"));
        addTail(rows, 21, 1340);

        VerificationFinding finding = findingOf(rows);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat((List<?>) ((Map<?, ?>) finding.details().get("reorderHypothesis")).get("windows"))
                .as("both pairs disagree, each in its own run").hasSize(2);
        assertThat(finding.details()).containsEntry(BalanceChainValidator.SAME_DAY_ORDER_ONLY, true);
    }

    @Test
    void theSamePairAcrossTwoDays_isNotSameDayOrder() {
        VerificationFinding finding = findingOf(transposedPair("2026-07-16", "2026-07-17"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).doesNotContainKey(BalanceChainValidator.SAME_DAY_ORDER_ONLY);
    }

    @Test
    void balancesSwappedBetweenRows_isNotSameDayOrder() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1150.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1100.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        addTail(rows, 17, 1090);

        assertThat(findingOf(rows).details()).doesNotContainKey(BalanceChainValidator.SAME_DAY_ORDER_ONLY);
    }

    @Test
    void aReorderPlusOneMisreadAmountElsewhere_isNotSameDayOrderOnly() {
        List<StagedRow> rows = transposedPair("2026-07-16", "2026-07-16");
        // A tail debit read as 15 instead of 10: its printed balance no longer follows.
        StagedRow misread = rows.get(rows.size() - 2);
        rows.set(rows.size() - 2, row(misread.date().toString(), misread.description(), "15.00", "EXPENSE",
                misread.balanceAfter().toPlainString()));

        VerificationFinding finding = findingOf(rows);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).doesNotContainKey(BalanceChainValidator.SAME_DAY_ORDER_ONLY);
    }

    @Test
    void aCleanStatement_carriesNoFlag() {
        List<StagedRow> rows = openingRun();
        addTail(rows, 16, 950);

        VerificationFinding finding = findingOf(rows);

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).doesNotContainKey(BalanceChainValidator.SAME_DAY_ORDER_ONLY);
    }

    // ---------- the rule on its own ----------

    private static Map<String, Object> explainedDetails() {
        return Map.of("gapsCancel", true, "reorderHypothesis", Map.of("tested", true,
                "windows", List.of(Map.of("tested", true, "reconciles", true))));
    }

    @Test
    void theRuleNeedsAWarning_cancellingGaps_andEveryWindowTestedAndReconciling() {
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", explainedDetails())).isTrue();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("FAILED", explainedDetails())).isFalse();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", null)).isFalse();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", Map.of("gapsCancel", false,
                "reorderHypothesis", explainedDetails().get("reorderHypothesis")))).isFalse();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", Map.of("gapsCancel", true,
                "reorderHypothesis", Map.of("tested", true, "moreWindowsNotTested", true,
                        "windows", List.of(Map.of("tested", true, "reconciles", true)))))).isFalse();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", Map.of("gapsCancel", true,
                "reorderHypothesis", Map.of("tested", true, "windows", List.of(
                        Map.of("tested", true, "reconciles", true),
                        Map.of("tested", false, "reason", "NOT_TESTED_DIFFERENT_DAYS")))))).isFalse();
        assertThat(BalanceChainValidator.explainedBySameDayOrder("WARNING", Map.of("gapsCancel", true,
                "reorderHypothesis", Map.of("tested", true, "windows", List.of(
                        Map.of("tested", true, "reconciles", false)))))).isFalse();
    }

    // ---------- what it does to the top line ----------

    @Test
    void aStatementWhoseOnlyWarningIsSameDayOrder_isClean() {
        VerificationFinding finding = findingOf(transposedPair("2026-07-16", "2026-07-16"));

        assertThat(ImportReliabilityStatusDeriver.derive(List.of(finding), false, TextSource.NATIVE_PDF))
                .isEqualTo(ImportReliabilityStatus.CLEAN);
    }

    @Test
    void sameDayOrderAlongsideOcrOrAnotherWarning_stillRecommendsReview() {
        VerificationFinding finding = findingOf(transposedPair("2026-07-16", "2026-07-16"));

        assertThat(ImportReliabilityStatusDeriver.derive(List.of(finding), false, TextSource.OCR))
                .isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
        assertThat(ImportReliabilityStatusDeriver.derive(
                List.of(finding, new VerificationFinding("SUMMARY_TOTALS", "WARNING", Map.of())),
                false, TextSource.NATIVE_PDF)).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    @Test
    void aBalanceChainWarningNotExplainedBySameDayOrder_stillRecommendsReview() {
        VerificationFinding finding = findingOf(transposedPair("2026-07-16", "2026-07-17"));

        assertThat(ImportReliabilityStatusDeriver.derive(List.of(finding), false, TextSource.NATIVE_PDF))
                .isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }
}
