package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SignedAmountColumn}: when an unsigned amount in one AMOUNT column is a credit. */
class SignedAmountColumnTest {

    private static Map<String, String> row(String amount, String balance) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("DATE", "15 Sep '26");
        row.put("DETAILS", "SAMPLE");
        if (amount != null) row.put("AMOUNT", amount);
        if (balance != null) row.put("BALANCE", balance);
        return row;
    }

    private static List<String> amounts(List<Map<String, String>> rows) {
        return rows.stream().map(r -> r.get("AMOUNT")).toList();
    }

    @Test
    void aBalanceThatReconcilesAsPrintedMarksTheUnsignedAmountsAsCredits() {
        List<Map<String, String>> rows = List.of(
                row("₹100.00", "₹118.20"), row("₹6,000.00", "₹6,118.20"),
                row("-₹1,954.00", "₹4,164.20"), row("₹0.87", "₹4,165.07"), row("-₹122.30", "₹4,042.77"));

        assertThat(amounts(SignedAmountColumn.markCredits(rows, null)))
                .containsExactly("+₹100.00", "+₹6,000.00", "-₹1,954.00", "+₹0.87", "-₹122.30");
    }

    @Test
    void aBalanceThatDoesNotReconcileAsPrintedLeavesTheColumnAlone() {
        // Every amount a debit by the default reading -- the balance falls with each one.
        List<Map<String, String>> rows = List.of(
                row("100.00", "900.00"), row("-50.00", "850.00"), row("25.00", "825.00"), row("10.00", "815.00"));

        assertThat(SignedAmountColumn.markCredits(rows, null)).isSameAs(rows);
    }

    @Test
    void aMonthWithNoDebits_isAllCredits_whenTheBalanceRisesByEveryAmount() {
        List<Map<String, String>> rows = List.of(
                row("₹6,000.00", "₹6,018.20"), row("₹0.87", "₹6,019.07"), row("₹0.26", "₹6,019.33"),
                row("₹0.25", "₹6,019.58"));

        assertThat(amounts(SignedAmountColumn.markCredits(rows, null)))
                .containsExactly("+₹6,000.00", "+₹0.87", "+₹0.26", "+₹0.25");
    }

    @Test
    void aMonthWithNoDebits_needsEveryPair_withNoMinusSignToGoOn() {
        // Three of four pairs rise by their amount (75%); one is contradicted. With no minus sign
        // establishing the convention, that is not enough.
        List<Map<String, String>> rows = List.of(row("100.00", "100.00"), row("50.00", "150.00"),
                row("25.00", "175.00"), row("10.00", "165.00"), row("5.00", "170.00"));

        assertThat(SignedAmountColumn.markCredits(rows, null)).isSameAs(rows);
    }

    @Test
    void tooFewReconcilingPairsAreNotEnoughEvidence() {
        List<Map<String, String>> rows = List.of(row("100.00", "100.00"), row("-50.00", "50.00"), row("25.00", "75.00"));

        assertThat(SignedAmountColumn.markCredits(rows, null)).isSameAs(rows);
    }

    @Test
    void aColumnThatAlreadySaysWhichWayIsLeftAlone() {
        List<Map<String, String>> withMarker = List.of(
                row("100.00 Cr", "100.00"), row("-50.00", "50.00"), row("25.00", "75.00"), row("10.00", "85.00"));
        assertThat(SignedAmountColumn.markCredits(withMarker, null)).isSameAs(withMarker);

        Map<String, String> withDeposit = row("-50.00", "50.00");
        withDeposit.put("Deposit", "");
        List<Map<String, String>> withDirectionColumn = List.of(
                row("100.00", "100.00"), withDeposit, row("25.00", "75.00"), row("10.00", "85.00"));
        assertThat(SignedAmountColumn.markCredits(withDirectionColumn, null)).isSameAs(withDirectionColumn);
    }

    @Test
    void noBalanceColumnMeansNoEvidence() {
        List<Map<String, String>> rows = List.of(row("100.00", null), row("-50.00", null), row("25.00", null));

        assertThat(SignedAmountColumn.markCredits(rows, null)).isSameAs(rows);
    }
}
