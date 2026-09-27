package com.finora.imports.refresh;

import com.finora.entity.Transaction;
import com.finora.imports.refresh.StatementRefreshDiff.Field;
import com.finora.imports.refresh.StatementRefreshDiff.FreshRow;
import com.finora.imports.refresh.StatementRefreshDiff.KnownKind;
import com.finora.imports.refresh.StatementRefreshDiff.KnownRow;
import com.finora.imports.refresh.StatementRefreshDiff.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementRefreshDiffTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 3);

    private static KnownRow live(int pos, LocalDate date, String desc, String amount, Transaction.EditableField... edited) {
        return new KnownRow(UUID.randomUUID(), KnownKind.LIVE, pos, date, desc, new BigDecimal(amount), "EXPENSE",
                null, null, Set.of(edited));
    }

    private static KnownRow deleted(int pos, LocalDate date, String desc, String amount) {
        return new KnownRow(UUID.randomUUID(), KnownKind.DELETED, pos, date, desc, new BigDecimal(amount), "EXPENSE",
                null, null, Set.of());
    }

    private static KnownRow excluded(int pos, LocalDate date, String desc, String amount) {
        return new KnownRow(null, KnownKind.EXCLUDED, pos, date, desc, new BigDecimal(amount), "EXPENSE",
                null, null, Set.of());
    }

    private static FreshRow fresh(int pos, LocalDate date, String desc, String amount) {
        return new FreshRow(pos, date, desc, new BigDecimal(amount), "EXPENSE", null, null);
    }

    @Test
    void theSameRowsReadTheSameWay_changeNothing() {
        Result r = StatementRefreshDiff.compute(
                List.of(live(0, D1, "GROCER", "450.00"), live(1, D2, "CAFE", "120.00")),
                List.of(fresh(0, D1, "GROCER", "450"), fresh(1, D2, "CAFE", "120.00")));

        assertThat(r.unchanged()).isEqualTo(2);
        assertThat(r.hasChanges()).isFalse();
    }

    @Test
    void aDescriptionReadDifferently_isOneChangedRow() {
        KnownRow row = live(0, D1, "GROC", "450.00");
        Result r = StatementRefreshDiff.compute(List.of(row), List.of(fresh(0, D1, "GROCER STORE", "450.00")));

        assertThat(r.changed()).singleElement().satisfies(c -> {
            assertThat(c.transactionId()).isEqualTo(row.transactionId());
            assertThat(c.changes()).extracting(StatementRefreshDiff.FieldChange::field).containsExactly(Field.DESCRIPTION);
        });
        assertThat(r.added()).isEmpty();
        assertThat(r.removed()).isEmpty();
    }

    @Test
    void aDateReadDifferently_isOneChangedRow() {
        Result r = StatementRefreshDiff.compute(List.of(live(0, D1, "GROCER", "450.00")),
                List.of(fresh(0, D2, "GROCER", "450.00")));

        assertThat(r.changed()).singleElement().satisfies(c ->
                assertThat(c.changes()).singleElement().satisfies(fc -> {
                    assertThat(fc.field()).isEqualTo(Field.DATE);
                    assertThat(fc.before()).isEqualTo("2026-07-01");
                    assertThat(fc.after()).isEqualTo("2026-07-02");
                }));
    }

    @Test
    void anAmountReadDifferently_isOneChangedRow() {
        Result r = StatementRefreshDiff.compute(List.of(live(0, D1, "GROCER", "45.00")),
                List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.changed()).singleElement().satisfies(c ->
                assertThat(c.changes()).extracting(StatementRefreshDiff.FieldChange::field).containsExactly(Field.AMOUNT));
    }

    @Test
    void aRowTheOldParserMissed_isAdded_andTheShiftedPositionsAfterItDoNotMatter() {
        Result r = StatementRefreshDiff.compute(
                List.of(live(0, D1, "GROCER", "450.00"), live(1, D3, "CAFE", "120.00")),
                List.of(fresh(0, D1, "GROCER", "450.00"), fresh(1, D2, "TAX", "18.00"), fresh(2, D3, "CAFE", "120.00")));

        assertThat(r.unchanged()).isEqualTo(2);
        assertThat(r.added()).extracting(FreshRow::description).containsExactly("TAX");
        assertThat(r.removed()).isEmpty();
    }

    @Test
    void aRowTheNewParserNoLongerFinds_isRemoved() {
        KnownRow furniture = live(1, D2, "PAGE 2 OF 3", "3.00");
        Result r = StatementRefreshDiff.compute(List.of(live(0, D1, "GROCER", "450.00"), furniture),
                List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.removed()).containsExactly(furniture);
        assertThat(r.conflicts()).isEmpty();
    }

    @Test
    void aRowTheUserDeleted_staysDeleted_ratherThanComingBackAsAdded() {
        Result r = StatementRefreshDiff.compute(List.of(deleted(0, D1, "GROCER", "450.00")),
                List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.staysDeleted()).isEqualTo(1);
        assertThat(r.added()).isEmpty();
        assertThat(r.hasChanges()).isFalse();
    }

    @Test
    void aRowTheUserLeftOutAtImport_staysExcluded_evenIfTheParserNowReadsItsDescriptionDifferently() {
        Result r = StatementRefreshDiff.compute(List.of(excluded(0, D1, "GROC", "450.00")),
                List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.staysExcluded()).isEqualTo(1);
        assertThat(r.added()).isEmpty();
        assertThat(r.hasChanges()).isFalse();
    }

    @Test
    void aFieldTheUserEdited_isKept_soADifferenceThereIsNotAChange() {
        // The user corrected the date; the parser still reads the original. Paired on the other
        // three fields, and the date difference is theirs to keep.
        Result r = StatementRefreshDiff.compute(
                List.of(live(0, D2, "GROCER", "450.00", Transaction.EditableField.DATE)),
                List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.hasChanges()).isFalse();
    }

    @Test
    void anEditedRowThatNoLongerPairs_isAConflict_notARemoval() {
        // The user corrected the amount; the parser now also reads the description differently.
        // Two fields differ, so nothing pairs -- but deleting this row would lose the correction.
        KnownRow edited = live(0, D1, "GROC", "500.00", Transaction.EditableField.AMOUNT);
        Result r = StatementRefreshDiff.compute(List.of(edited), List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.conflicts()).containsExactly(edited);
        assertThat(r.removed()).isEmpty();
        assertThat(r.added()).hasSize(1);
        assertThat(r.hasChanges()).isTrue();
    }

    @Test
    void twoFieldsChangingAtOnce_areRemovedAndAdded_notMarriedToAnUnrelatedRow() {
        KnownRow row = live(0, D1, "GROC", "45.00");
        Result r = StatementRefreshDiff.compute(List.of(row), List.of(fresh(0, D1, "GROCER", "450.00")));

        assertThat(r.removed()).containsExactly(row);
        assertThat(r.added()).hasSize(1);
        assertThat(r.changed()).isEmpty();
    }

    @Test
    void identicalRows_pairInDocumentOrder() {
        KnownRow first = live(0, D1, "METRO FARE", "45.00");
        KnownRow second = live(1, D1, "METRO FARE", "45.00");
        Result r = StatementRefreshDiff.compute(List.of(second, first),
                List.of(fresh(0, D1, "METRO FARE", "45.00"), fresh(1, D1, "METRO FARE", "45.00")));

        assertThat(r.unchanged()).isEqualTo(2);
        assertThat(r.hasChanges()).isFalse();
    }

    @Test
    void aRunningBalanceOrReferenceReadDifferently_isAChange_andScaleAloneIsNot() {
        KnownRow row = new KnownRow(UUID.randomUUID(), KnownKind.LIVE, 0, D1, "GROCER", new BigDecimal("450.00"),
                "EXPENSE", new BigDecimal("1000.00"), "REF1", Set.of());
        Result sameScaleOnly = StatementRefreshDiff.compute(List.of(row),
                List.of(new FreshRow(0, D1, "GROCER", new BigDecimal("450"), "EXPENSE", new BigDecimal("1000"), "REF1")));
        Result balanceFixed = StatementRefreshDiff.compute(List.of(row),
                List.of(new FreshRow(0, D1, "GROCER", new BigDecimal("450"), "EXPENSE", new BigDecimal("550.00"), "REF1")));

        assertThat(sameScaleOnly.hasChanges()).isFalse();
        assertThat(balanceFixed.changed()).singleElement().satisfies(c ->
                assertThat(c.changes()).extracting(StatementRefreshDiff.FieldChange::field).containsExactly(Field.BALANCE_AFTER));
    }

    @Test
    void aNullDescription_isNotTheSameAsAnyDescription() {
        KnownRow noDescription = live(0, D1, null, "45.00");
        Result r = StatementRefreshDiff.compute(List.of(noDescription), List.of(fresh(0, D1, null, "45.00")));
        assertThat(r.unchanged()).isEqualTo(1);

        // A null description reads as a change to a real one, not as a match on "any description"
        // in the first pass.
        Result described = StatementRefreshDiff.compute(List.of(noDescription), List.of(fresh(0, D1, "GROCER", "45.00")));
        assertThat(described.changed()).singleElement().satisfies(c ->
                assertThat(c.changes()).extracting(StatementRefreshDiff.FieldChange::field).containsExactly(Field.DESCRIPTION));
    }

    @Test
    void anIncomeAndAnExpenseOfTheSameAmount_neverPair() {
        KnownRow expense = live(0, D1, "TRANSFER", "100.00");
        FreshRow income = new FreshRow(0, D1, "TRANSFER", new BigDecimal("100.00"), "INCOME", null, null);
        Result r = StatementRefreshDiff.compute(List.of(expense), List.of(income));

        assertThat(r.removed()).containsExactly(expense);
        assertThat(r.added()).containsExactly(income);
    }
}
