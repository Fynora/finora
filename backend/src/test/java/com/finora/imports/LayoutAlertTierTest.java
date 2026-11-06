package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.finora.imports.LayoutAlertTier.DIGEST;
import static com.finora.imports.LayoutAlertTier.IMMEDIATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tier is DIGEST only for a positive finding; everything else, including every surprise, is
 * IMMEDIATE. The balance-chain cases run the real validator on built statements rather than a
 * hand-made map, so a change to what the validator emits breaks these.
 */
class LayoutAlertTierTest {

    private static final String BC = "VERIFICATION_NOT_PASSED:BALANCE_CHAIN";
    private final BalanceChainValidator validator = new BalanceChainValidator();

    private static StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(LocalDate.parse(date), description, new BigDecimal(amount),
                type, "Other", "default", null, false, null,
                balanceAfter == null ? null : new BigDecimal(balanceAfter));
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

    private static void tail(List<StagedRow> rows, int from, int balanceBefore) {
        int balance = balanceBefore;
        for (int day = from; day < from + 3; day++) {
            balance -= 10;
            rows.add(row("2026-07-" + day, "TAIL " + day, "10.00", "EXPENSE", balance + ".00"));
        }
    }

    /** X and Y transposed on one day: a warning that re-ordering fully explains. */
    private static List<StagedRow> explainedWarning() {
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        tail(rows, 17, 1090);
        return rows;
    }

    private VerificationReport reportOf(List<StagedRow> rows, String expectedOutcome) {
        VerificationReport report = new VerificationReport(List.of(validator.report(rows, null).findings().get(0)));
        assertThat(report.findings().get(0).outcome()).as("fixture outcome").isEqualTo(expectedOutcome);
        return report;
    }

    @Test
    void aNewLayoutAloneWaitsForTheDigest() {
        assertThat(LayoutAlertTier.of(List.of("NEW_LAYOUT"), List.of())).isEqualTo(DIGEST);
        assertThat(LayoutAlertTier.of(List.of("NEW_LAYOUT", "HOLDER_NAME_UNREADABLE"), null)).isEqualTo(DIGEST);
    }

    @Test
    void aFullyExplainedWarningWaitsForTheDigest_withOrWithoutANewLayout() {
        var report = reportOf(explainedWarning(), "WARNING");

        assertThat(LayoutAlertTier.of(List.of(BC), List.of(report))).isEqualTo(DIGEST);
        assertThat(LayoutAlertTier.of(List.of("NEW_LAYOUT", BC), List.of(report))).isEqualTo(DIGEST);
    }

    @Test
    void aFailedChainIsUrgent() {
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("2026-07-10", "OPENING CREDIT", "1000.00", "INCOME", "1000.00"));
        for (int day = 11; day <= 14; day++) rows.add(row("2026-07-" + day, "STUCK", "10.00", "EXPENSE", "1000.00"));

        assertThat(LayoutAlertTier.of(List.of(BC), List.of(reportOf(rows, "FAILED")))).isEqualTo(IMMEDIATE);
    }

    @Test
    void aWarningWhoseGapsDoNotCancelIsUrgent() {
        // A deposit recorded as an expense: one row, out by twice its own amount.
        List<StagedRow> rows = new ArrayList<>(List.of(
                row("2026-07-10", "OPENING SPEND", "1000.00", "EXPENSE", "9000.00"),
                row("2026-07-11", "SALARY", "25000.00", "EXPENSE", "34000.00"),
                row("2026-07-12", "RENT", "5000.00", "EXPENSE", "29000.00"),
                row("2026-07-13", "GROCERIES", "1250.00", "EXPENSE", "27750.00")));
        var report = reportOf(rows, "WARNING");
        assertThat(report.findings().get(0).details()).containsEntry("gapsCancel", false);

        assertThat(LayoutAlertTier.of(List.of(BC), List.of(report))).isEqualTo(IMMEDIATE);
    }

    @Test
    void aWarningThatCancelsButIsNotExplainedByReorderingIsUrgent() {
        // The printed balances traded places between two rows: the gaps cancel, no order fixes it.
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1150.00"));
        rows.add(row("2026-07-16", "Y CREDIT", "200.00", "INCOME", "1100.00"));
        rows.add(row("2026-07-16", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        tail(rows, 17, 1090);
        var report = reportOf(rows, "WARNING");
        assertThat(report.findings().get(0).details()).containsEntry("gapsCancel", true);

        assertThat(LayoutAlertTier.of(List.of(BC), List.of(report))).isEqualTo(IMMEDIATE);
    }

    @Test
    void aRunThatWasNotTestedIsUrgent() {
        // The disagreeing rows sit on different days, so re-ordering is not tried.
        List<StagedRow> rows = openingRun();
        rows.add(row("2026-07-16", "X DEBIT", "50.00", "EXPENSE", "1100.00"));
        rows.add(row("2026-07-17", "Y CREDIT", "200.00", "INCOME", "1150.00"));
        rows.add(row("2026-07-17", "Z DEBIT", "10.00", "EXPENSE", "1090.00"));
        tail(rows, 18, 1090);

        assertThat(LayoutAlertTier.of(List.of(BC), List.of(reportOf(rows, "WARNING")))).isEqualTo(IMMEDIATE);
    }

    @Test
    void missingOrMalformedDetailsAreUrgent() {
        Map<String, Object> noHypothesis = Map.of("gapsCancel", true, "rowsChecked", 100);
        Map<String, Object> malformed = Map.of("gapsCancel", true, "reorderHypothesis", "yes");
        Map<String, Object> emptyWindows = Map.of("gapsCancel", true,
                "reorderHypothesis", Map.of("tested", true, "windows", List.of()));
        Map<String, Object> moreNotTested = Map.of("gapsCancel", true, "reorderHypothesis", Map.of(
                "tested", true, "moreWindowsNotTested", true,
                "windows", List.of(Map.of("tested", true, "reconciles", true))));
        for (Map<String, Object> details : List.of(noHypothesis, malformed, emptyWindows, moreNotTested)) {
            var report = new VerificationReport(List.of(new VerificationFinding("BALANCE_CHAIN", "WARNING", details)));
            assertThat(LayoutAlertTier.of(List.of(BC), List.of(report))).as(details.toString()).isEqualTo(IMMEDIATE);
        }
        var nullDetails = new VerificationReport(List.of(new VerificationFinding("BALANCE_CHAIN", "WARNING", null)));
        assertThat(LayoutAlertTier.of(List.of(BC), List.of(nullDetails))).isEqualTo(IMMEDIATE);
    }

    @Test
    void anyOtherReasonIsUrgent_evenBesideAnExplainedWarning() {
        var report = reportOf(explainedWarning(), "WARNING");

        for (String reason : List.of("STAGING_FAILED", "IDENTITY_CONFLICT", "BLANK_DESCRIPTIONS",
                "VERIFICATION_NOT_PASSED:STATEMENT_TOTALS", "SOMETHING_NEW")) {
            assertThat(LayoutAlertTier.of(List.of(reason), List.of(report))).as(reason).isEqualTo(IMMEDIATE);
            assertThat(LayoutAlertTier.of(List.of(BC, reason), List.of(report))).as(reason).isEqualTo(IMMEDIATE);
        }
    }

    @Test
    void aFailingSectionBesideABenignOneIsUrgent() {
        List<StagedRow> stuck = new ArrayList<>();
        stuck.add(row("2026-07-10", "OPENING CREDIT", "1000.00", "INCOME", "1000.00"));
        for (int day = 11; day <= 14; day++) stuck.add(row("2026-07-" + day, "STUCK", "10.00", "EXPENSE", "1000.00"));

        assertThat(LayoutAlertTier.of(List.of(BC),
                List.of(reportOf(explainedWarning(), "WARNING"), reportOf(stuck, "FAILED")))).isEqualTo(IMMEDIATE);
    }

    @Test
    void aBalanceChainReasonWithNothingFromThisRunToExplainItIsUrgent() {
        assertThat(LayoutAlertTier.of(List.of(BC), null)).isEqualTo(IMMEDIATE);
        assertThat(LayoutAlertTier.of(List.of(BC), List.of())).isEqualTo(IMMEDIATE);
        assertThat(LayoutAlertTier.of(List.of(BC), Arrays.asList((VerificationReport) null))).isEqualTo(IMMEDIATE);
    }

    @Test
    void noReasonsAtAllIsUrgent() {
        assertThat(LayoutAlertTier.of(null, List.of())).isEqualTo(IMMEDIATE);
        assertThat(LayoutAlertTier.of(List.of(), List.of())).isEqualTo(IMMEDIATE);
    }
}
