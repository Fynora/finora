package com.finora.imports.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic narrations only -- shaped like real UPI/ATM/CDM lines, never copied from a statement. */
class FlowPatternAnalysisTest {

    private static final LocalDate D0 = LocalDate.of(2026, 7, 1);

    private static FlowPatternAnalysis.Row in(int day, String amount, String description) {
        return new FlowPatternAnalysis.Row(D0.plusDays(day), new BigDecimal(amount), true, description);
    }

    private static FlowPatternAnalysis.Row out(int day, String amount, String description) {
        return new FlowPatternAnalysis.Row(D0.plusDays(day), new BigDecimal(amount), false, description);
    }

    private static final String[] FIRST = {"ASHA", "RAVI", "MEENA", "KIRAN", "SUNIL", "LATA", "VIJAY", "NEHA"};
    private static final String[] LAST = {"VERMA", "RAO", "SHARMA", "IYER"};

    /** The UPI narration shape CounterpartyClassifier reads as a named individual (see its own test). */
    private static String person(String first, String last, int ref) {
        return "UPI-" + first + " " + last + "-" + first.toLowerCase() + last.toLowerCase() + "@ybl-REF" + ref;
    }

    /** The n-th distinct synthetic person (n < 32). */
    private static String person(int n, int ref) {
        return person(FIRST[n % FIRST.length], LAST[n / FIRST.length], ref);
    }

    // ---- counterparty net position ----

    @Test void lendThenRepaid_isALedgerCandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "20000.00", person("ASHA", "VERMA", 1)),
                in(30, "10000.00", person("ASHA", "VERMA", 2)),
                in(60, "10000.00", person("ASHA", "VERMA", 3)));
        var candidates = FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("5000"), new BigDecimal("0.2"));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).net()).isEqualByComparingTo("0");
    }

    @Test void oneDirectionOnly_isNotACandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "3000.00", person("LATA", "RAO", 1)),
                out(30, "3000.00", person("LATA", "RAO", 2)));
        assertThat(FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("1000"), new BigDecimal("0.2"))).isEmpty();
    }

    @Test void lopsidedTwoWay_isNotACandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "20000.00", person("ASHA", "VERMA", 1)),
                in(5, "500.00", person("ASHA", "VERMA", 2)));
        assertThat(FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("5000"), new BigDecimal("0.2"))).isEmpty();
    }

    // ---- rhythm ----

    @Test void manyPayersInOneWeek_isBurst() {
        List<FlowPatternAnalysis.Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(in(i % 3, "1100.00", person(i, i)));
        rows.add(out(60, "100.00", "UPI MERCHANTCO PVT LTD")); // statement spans ~9 weeks
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.BURST);
    }

    @Test void manyPayersEveryWeek_isSteady() {
        List<FlowPatternAnalysis.Row> rows = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            for (int k = 0; k < 4; k++) {
                int n = w * 4 + k;
                rows.add(in(w * 7 + k, (40 + n * 7) + ".00", person(n, n)));
            }
        }
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.STEADY);
    }

    @Test void samePayersOncePerMonth_isMonthly() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(2, "15000.00", person("RAVI", "SHARMA", 1)), in(3, "12000.00", person("MEENA", "IYER", 2)),
                in(33, "15000.00", person("RAVI", "SHARMA", 3)), in(34, "12000.00", person("MEENA", "IYER", 4)),
                in(63, "15000.00", person("RAVI", "SHARMA", 5)), in(64, "12000.00", person("MEENA", "IYER", 6)));
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.MONTHLY);
    }

    @Test void fewerThanThreePersonCredits_isNoneOrOneOff() {
        assertThat(FlowPatternAnalysis.personCreditRhythm(List.of()).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.NONE);
        assertThat(FlowPatternAnalysis.personCreditRhythm(List.of(in(1, "5000.00", person("KIRAN", "RAO", 1)))).rhythm())
                .isEqualTo(FlowPatternAnalysis.Rhythm.ONE_OFF);
    }

    // ---- cash round-trips ----

    @Test void cashDepositAfterAtm_withinWindow_isRoundTrip_upToUnspent() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "10000.00", "ATM WDL 0001 CITY"),
                in(10, "8000.00", "CASH DEPOSIT CDM 0002"),
                in(12, "5000.00", "CASH DEPOSIT CDM 0003"),   // only 2000 of the withdrawal is left: not a round-trip
                in(50, "1000.00", "CASH DEPOSIT CDM 0004"));  // outside the 30-day window
        assertThat(FlowPatternAnalysis.cashRoundTripDeposits(rows, 30))
                .extracting(FlowPatternAnalysis.Row::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("8000.00"));
    }

    // ---- pass-through ----

    @Test void severalPersonCreditsThenOneMatchingDebit_isPassThrough() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(0, "2000.00", person("SUNIL", "VERMA", 1)),
                in(1, "2000.00", person("VIJAY", "RAO", 2)),
                in(2, "2000.00", person("NEHA", "IYER", 3)),
                out(4, "6000.00", "UPI GIFTSHOP PVT LTD"));
        assertThat(FlowPatternAnalysis.passThroughs(rows, 14, new BigDecimal("0.05"), new BigDecimal("2000"))).hasSize(1);
    }

    @Test void unrelatedCreditsAndDebit_isNotPassThrough() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(0, "2000.00", person("SUNIL", "VERMA", 1)),
                out(4, "9000.00", "UPI GIFTSHOP PVT LTD"));
        assertThat(FlowPatternAnalysis.passThroughs(rows, 14, new BigDecimal("0.05"), new BigDecimal("2000"))).isEmpty();
    }

    // ---- fixture sanity: the synthetic shapes above must read as what they pretend to be ----

    @Test void syntheticPersonNarrations_classifyAsPersonsWithVpaKeys() {
        FlowPatternAnalysis.Row r = in(0, "1.00", person("ASHA", "VERMA", 1));
        assertThat(r.person()).isTrue();
        assertThat(r.counterpartyKey()).startsWith("vpa:");
    }
}
