package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stored report's old row numbers are converted only when it is known which list they counted,
 * and then exactly -- otherwise the finding comes back as stored and no row is marked. Synthetic
 * rows throughout.
 */
class StoredRowPositionsTest {

    /** A row shown at some place in the list, read from the document at {@code rowPosition}. */
    private static StagedRow row(String label, int day, Integer rowPosition) {
        StagedRow row = new StagedRow(LocalDate.of(2026, 7, day), "UPI/DR/ROW " + label, new BigDecimal("100.00"),
                "EXPENSE", "Other", "rule", null, false, null, null);
        return rowPosition == null ? row : row.withRowPosition(rowPosition);
    }

    // Printed newest first: G(1) F(2) E(3) D(4) C(5) B(6) A(7), with C, D and E on one day. Shown
    // sorted by date, same-day rows kept in printed order: A B E D C F G.
    private static List<StagedRow> newestFirstShown() {
        return List.of(row("A", 1, 7), row("B", 2, 6), row("E", 3, 3), row("D", 3, 4), row("C", 3, 5),
                row("F", 4, 2), row("G", 5, 1));
    }

    private static Map<String, Object> entry(int rowIndex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowIndex", rowIndex);
        m.put("expectedBalance", 9390.00);
        return m;
    }

    private static VerificationFinding balance(boolean withShapes, int... rowIndexes) {
        List<Object> discrepancies = new ArrayList<>();
        for (int r : rowIndexes) discrepancies.add(entry(r));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 7);
        details.put("discrepancies", discrepancies);
        if (withShapes) details.put("discrepancyShapes", List.of(Map.of("rowIndex", rowIndexes[0])));
        return new VerificationFinding(BalanceChainValidator.RULE, "WARNING", details);
    }

    private static VerificationFinding outliers(int... rowIndexes) {
        List<Object> list = new ArrayList<>();
        for (int r : rowIndexes) list.add(new LinkedHashMap<>(Map.of("rowIndex", r, "length", 162)));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 7);
        details.put("outliers", list);
        return new VerificationFinding(DescriptionCorruptionValidator.RULE, "WARNING", details);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> displayIndexes(VerificationFinding finding, String key) {
        return ((List<Map<String, Object>>) finding.details().get(key)).stream()
                .map(m -> m.get("displayIndex")).toList();
    }

    @Test
    void aBalanceFindingWrittenWithShapesAlreadyCountedTheRowsShown() {
        VerificationFinding converted = StoredRowPositions.withDisplayPositions(balance(true, 4, 1), newestFirstShown());

        assertThat(displayIndexes(converted, "discrepancies")).containsExactly(1, 4);
    }

    @Test
    void anOlderBalanceFindingCountedTheWalkWhichRanBackwardsOverANewestFirstStatement() {
        // The walk was A B C D E F G (the printed sequence reversed); C was its third step, 2. C is
        // shown fifth. These are the numbers main stored at aa94d63 for a statement of this shape.
        VerificationFinding converted = StoredRowPositions.withDisplayPositions(balance(false, 2), newestFirstShown());

        assertThat(displayIndexes(converted, "discrepancies")).containsExactly(4);
    }

    @Test
    void anOlderBalanceFindingOnAnOldestFirstStatementCountedThePrintedSequence() {
        // Printed A (1 Jul), B (2 Jul), H (1 Jul, back-dated). Shown by date: A, H, B.
        List<StagedRow> shown = List.of(row("A", 1, 1), row("H", 1, 3), row("B", 2, 2));
        // The walk did not reverse (the first row, 1 Jul, is not dated after the last, 1 Jul), so
        // walk step 2 is the third row printed: H, shown second.
        VerificationFinding converted = StoredRowPositions.withDisplayPositions(balance(false, 2), shown);

        assertThat(displayIndexes(converted, "discrepancies")).containsExactly(1);
    }

    @Test
    void anOutlierCountedThePrintedSequence() {
        // The sixth row printed is B, shown second.
        VerificationFinding converted = StoredRowPositions.withDisplayPositions(outliers(5), newestFirstShown());

        assertThat(displayIndexes(converted, "outliers")).containsExactly(1);
        assertThat(converted.details().get("rowsChecked")).isEqualTo(7);
    }

    @Test
    void severalAreListedDownTheListShown() {
        // Printed positions 0 (G, shown last) and 5 (B, shown second).
        VerificationFinding converted = StoredRowPositions.withDisplayPositions(outliers(0, 5), newestFirstShown());

        assertThat(displayIndexes(converted, "outliers")).containsExactly(1, 6);
    }

    @Test
    void aFindingThatAlreadyCarriesThePositionIsReturnedAsStored() {
        VerificationFinding fresh = outliers(5);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) fresh.details().get("outliers")).get(0);
        first.put("displayIndex", 1);

        assertThat(StoredRowPositions.withDisplayPositions(fresh, newestFirstShown())).isSameAs(fresh);
    }

    @Test
    void rowsWithoutPositionsConvertNothing() {
        // Staged before rows carried rowPosition, so the build that wrote the numbers is not known.
        List<StagedRow> shown = new ArrayList<>(newestFirstShown());
        shown.set(3, row("D", 3, null));
        VerificationFinding stored = balance(false, 2);

        assertThat(StoredRowPositions.withDisplayPositions(stored, shown)).isSameAs(stored);
        assertThat(displayIndexes(StoredRowPositions.withDisplayPositions(outliers(5), shown), "outliers"))
                .containsOnlyNulls();
    }

    @Test
    void twoRowsSharingAPositionConvertNothing() {
        List<StagedRow> shown = new ArrayList<>(newestFirstShown());
        shown.set(3, row("D", 3, 5));
        VerificationFinding stored = outliers(5);

        assertThat(StoredRowPositions.withDisplayPositions(stored, shown)).isSameAs(stored);
    }

    @Test
    void oneNumberThatCannotBePlacedLeavesTheWholeFindingAsStored() {
        VerificationFinding outOfRange = outliers(1, 7);
        VerificationFinding negative = balance(true, 2, -1);

        assertThat(StoredRowPositions.withDisplayPositions(outOfRange, newestFirstShown())).isSameAs(outOfRange);
        assertThat(StoredRowPositions.withDisplayPositions(negative, newestFirstShown())).isSameAs(negative);
    }

    @Test
    void aNumberThatIsNotAWholeNumberConvertsNothing() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outliers", List.of(new LinkedHashMap<>(Map.of("rowIndex", "5"))));
        VerificationFinding stored = new VerificationFinding(DescriptionCorruptionValidator.RULE, "WARNING", details);

        assertThat(StoredRowPositions.withDisplayPositions(stored, newestFirstShown())).isSameAs(stored);
    }

    @Test
    void otherChecksAndEmptyFindingsAreReturnedAsStored() {
        VerificationFinding ambiguity = new VerificationFinding("COLUMN_AMBIGUITY", "WARNING",
                new LinkedHashMap<>(Map.of("ambiguities", List.of(new LinkedHashMap<>(Map.of("rowIndex", 2))))));
        VerificationFinding clean = new VerificationFinding(BalanceChainValidator.RULE, "VERIFIED",
                new LinkedHashMap<>(Map.of("discrepancies", List.of())));
        VerificationFinding noDetails = new VerificationFinding(BalanceChainValidator.RULE, "VERIFIED", null);

        assertThat(StoredRowPositions.withDisplayPositions(ambiguity, newestFirstShown())).isSameAs(ambiguity);
        assertThat(StoredRowPositions.withDisplayPositions(clean, newestFirstShown())).isSameAs(clean);
        assertThat(StoredRowPositions.withDisplayPositions(noDetails, newestFirstShown())).isSameAs(noDetails);
        assertThat(StoredRowPositions.withDisplayPositions(null, newestFirstShown())).isNull();
        assertThat(displayIndexes(StoredRowPositions.withDisplayPositions(outliers(1), null), "outliers"))
                .containsOnlyNulls();
    }
}
