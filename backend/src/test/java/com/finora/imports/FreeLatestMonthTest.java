package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreeLatestMonthTest {

    private static StagedRow row(String date, String amount, String type, String balance) {
        return new StagedRow(LocalDate.parse(date), "ROW " + date, new BigDecimal(amount), type, "Other", "rule",
                null, false, null, balance == null ? null : new BigDecimal(balance));
    }

    private static List<StagedRow> rowsOn(String... dates) {
        List<StagedRow> rows = new ArrayList<>();
        for (String d : dates) rows.add(row(d, "10.00", "EXPENSE", null));
        return rows;
    }

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Nested
    class WhichMonth {
        @Test
        void aStatementEndingMidMonth_givesThePreviousFullMonth() {
            var month = FreeLatestMonth.lastFullMonth(d("2026-08-17"), d("2026-11-17"),
                    rowsOn("2026-08-20", "2026-10-05", "2026-11-10"));
            assertThat(month).isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void aStatementEndingOnAMonthsLastDay_givesThatMonth() {
            var month = FreeLatestMonth.lastFullMonth(d("2026-08-01"), d("2026-10-31"),
                    rowsOn("2026-08-03", "2026-10-30"));
            assertThat(month).isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void februaryInALeapYear_endsOnThe29th() {
            var month = FreeLatestMonth.lastFullMonth(d("2028-01-01"), d("2028-02-29"), rowsOn("2028-02-10"));
            assertThat(month).isEqualTo(new FreeLatestMonth.Month(d("2028-02-01"), d("2028-02-29")));
        }

        @Test
        void withoutAPrintedPeriod_theLastRowDecides_andAMonthIsFullOnlyToItsLastRow() {
            // Last row on 29 Oct: whether the file runs to 31 Oct is not known, so October is not offered.
            assertThat(FreeLatestMonth.lastFullMonth(null, null, rowsOn("2026-08-01", "2026-09-15", "2026-10-29")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-09-01"), d("2026-09-30")));
            assertThat(FreeLatestMonth.lastFullMonth(null, null, rowsOn("2026-08-01", "2026-09-15", "2026-10-31")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void halfAPrintedPeriod_isJudgedByTheRows() {
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-08-01"), null, rowsOn("2026-08-02", "2026-10-31")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void noWhollyCoveredMonth_offersNothing() {
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-01-02"), d("2026-02-08"), rowsOn("2026-01-05", "2026-02-07")))
                    .isNull();
        }

        @Test
        void aFinalMonthWithNoActivity_givesWayToTheLatestMonthWithTransactions() {
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-08-01"), d("2026-10-31"), rowsOn("2026-08-03", "2026-09-20")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-09-01"), d("2026-09-30")));
        }

        @Test
        void aPrintedPeriodOverstatingTheData_isReadDownToTheLastTransactionsMonth() {
            // A Slice statement printing its whole financial year, transactions only Jul to Oct.
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-04-01"), d("2027-03-31"),
                    rowsOn("2026-07-31", "2026-08-20", "2026-10-06")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void emptyFullMonths_arePassedOver_toTheLatestOneWithTransactions() {
            // Rows in Aug and Nov only, statement ends 17 Nov: October and September are whole but empty.
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-08-01"), d("2026-11-17"), rowsOn("2026-08-03", "2026-11-10")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-08-01"), d("2026-08-31")));
        }

        @Test
        void butNeverAMonthBeforeTheStatementStarts() {
            // Starts 2 Aug, so August is not whole; Sep and Oct are whole but empty: nothing to offer.
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-08-02"), d("2026-11-17"), rowsOn("2026-08-03", "2026-11-10")))
                    .isNull();
        }

        @Test
        void aReversedPrintedPeriod_isReadTheRightWayRound() {
            assertThat(FreeLatestMonth.lastFullMonth(d("2026-11-17"), d("2026-08-17"), rowsOn("2026-10-05")))
                    .isEqualTo(new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31")));
        }

        @Test
        void noDatesAtAll_offersNothing() {
            assertThat(FreeLatestMonth.lastFullMonth(null, null, List.of())).isNull();
        }
    }

    @Nested
    class TheMonthsBalances {
        private final FreeLatestMonth.Month october = new FreeLatestMonth.Month(d("2026-10-01"), d("2026-10-31"));

        @Test
        void readFromTheRunningBalance_whenTheChainAgrees() {
            List<StagedRow> rows = List.of(
                    row("2026-09-28", "50.00", "EXPENSE", "1000.00"),
                    row("2026-10-02", "100.00", "EXPENSE", "900.00"),
                    row("2026-10-15", "500.00", "INCOME", "1400.00"),
                    row("2026-10-31", "40.00", "EXPENSE", "1360.00"),
                    row("2026-11-03", "60.00", "EXPENSE", "1300.00"));

            var figures = FreeLatestMonth.figures(rows, october);

            assertThat(figures.openingBalance()).isEqualByComparingTo("1000.00");
            assertThat(figures.closingBalance()).isEqualByComparingTo("1360.00");
        }

        @Test
        void newestFirst_withSeveralRowsOnTheBoundaryDays_isReadFromTheChainNotTheOrder() {
            // Listed newest first, as some banks do; the same day's rows out of order too.
            List<StagedRow> rows = List.of(
                    row("2026-11-03", "60.00", "EXPENSE", "1300.00"),
                    row("2026-10-31", "40.00", "EXPENSE", "1360.00"),
                    row("2026-10-31", "100.00", "INCOME", "1400.00"),
                    row("2026-10-02", "100.00", "EXPENSE", "1300.00"),
                    row("2026-10-02", "300.00", "INCOME", "1400.00"),
                    row("2026-09-28", "50.00", "EXPENSE", "1100.00"));

            var figures = FreeLatestMonth.figures(rows, october);

            assertThat(figures.openingBalance()).isEqualByComparingTo("1100.00");
            assertThat(figures.closingBalance()).isEqualByComparingTo("1360.00");
        }

        @Test
        void aRowWithoutABalance_givesNoBalances() {
            List<StagedRow> rows = List.of(
                    row("2026-10-02", "100.00", "EXPENSE", "900.00"),
                    row("2026-10-15", "500.00", "INCOME", null));
            assertThat(FreeLatestMonth.figures(rows, october)).isEqualTo(FreeLatestMonth.Figures.NONE);
        }

        @Test
        void aChainThatDoesNotAddUp_givesNoBalances() {
            List<StagedRow> rows = List.of(
                    row("2026-10-02", "100.00", "EXPENSE", "900.00"),
                    row("2026-10-15", "500.00", "INCOME", "1450.00")); // should be 1400.00
            assertThat(FreeLatestMonth.figures(rows, october)).isEqualTo(FreeLatestMonth.Figures.NONE);
        }

        @Test
        void aCardStyleBalanceThatRisesWithSpending_givesNoBalances() {
            List<StagedRow> rows = List.of(
                    row("2026-10-02", "100.00", "EXPENSE", "1100.00"),
                    row("2026-10-15", "200.00", "EXPENSE", "1300.00"));
            assertThat(FreeLatestMonth.figures(rows, october)).isEqualTo(FreeLatestMonth.Figures.NONE);
        }

        @Test
        void aRowOfUnknownDirection_givesNoBalances() {
            List<StagedRow> rows = List.of(row("2026-10-02", "100.00", "TRANSFER", "900.00"));
            assertThat(FreeLatestMonth.figures(rows, october)).isEqualTo(FreeLatestMonth.Figures.NONE);
        }
    }
}
