package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.BalanceChainValidator.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

/**
 * The validator's whole purpose is catching parses that every other stage reported as successful,
 * so these tests are written as the failure SHAPES rather than as method coverage: wrong amount,
 * wrong direction, dropped row, duplicated row. Each one is a real way an importer goes wrong, and
 * each breaks the statement's own arithmetic in a way nothing else was checking.
 */
class BalanceChainValidatorTest {

    private final BalanceChainValidator validator = new BalanceChainValidator();

    /** amount and balanceAfter are both nullable -- a real staged row can be missing either, and
     *  the two absences mean different things to the chain (see the gap tests at the bottom). */
    private StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(
                LocalDate.parse(date), description, amount == null ? null : new BigDecimal(amount), type,
                "Other", "default", null, false, null,
                balanceAfter == null ? null : new BigDecimal(balanceAfter));
    }

    /** The motivating statement, parsed correctly: one deposit and three premium debits. */
    private List<StagedRow> correctlyParsedStatement() {
        return List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));
    }

    @Test
    void verifiesAStatementWhoseRowsReconcile() {
        var result = validator.validate(correctlyParsedStatement());

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.isVerified()).isTrue();
        assertThat(result.summary()).contains("verified");
    }

    @Test
    void catchesAWrongAmount_andNamesTheRowAndTheShortfall() {
        // Exactly the bug that prompted this: the unused Deposits column held 0.00, the amount
        // picker took it, and the real 436.00 premium was never reached. Every stage reported
        // success. The balance chain does not.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "0.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);

        var wrong = result.discrepancies().get(0);
        assertThat(wrong.rowIndex()).isEqualTo(2);
        assertThat(wrong.description()).isEqualTo("PMJJBY PREMIUM");
        assertThat(wrong.expectedBalance()).isEqualByComparingTo("24980.00");
        assertThat(wrong.actualBalance()).isEqualByComparingTo("24544.00");
        // "We are short by 436.00 here" -- the premium that was read as zero.
        assertThat(wrong.difference()).isEqualByComparingTo("-436.00");
    }

    @Test
    void catchesAWrongDirection() {
        // A deposit recorded as an expense moves the balance the wrong way, so the mismatch is
        // TWICE the amount -- a louder signal than the transaction merely being absent.
        var rows = List.of(
                row("2026-07-10", "OPENING SPEND", "1000.00", "EXPENSE", "9000.00"),
                row("2026-07-11", "SALARY", "25000.00", "EXPENSE", "34000.00"),
                row("2026-07-12", "RENT", "5000.00", "EXPENSE", "29000.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).description()).isEqualTo("SALARY");
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("50000.00");
    }

    @Test
    void catchesADroppedRow() {
        // The middle transaction never made it through. The balance jumps without an explanation.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        // One pair is below the minimum for a verdict on its own -- see MIN_PAIRS_FOR_A_VERDICT.
        // Reported as not-applicable rather than as a confident failure, which is the honest
        // answer from a single comparison.
        assertThat(result.status()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void catchesADroppedRow_whenThereIsEnoughChainToJudge() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                // PMJJBY (436.00) dropped entirely -- the next row's balance cannot be reached.
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("-436.00");
    }

    @Test
    void catchesADuplicatedRow() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isNotEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).isNotEmpty();
    }

    @Test
    void reportsFailedWhenMostRowsAreWrong() {
        // A whole column misread breaks most rows, not a scattered few -- which is the distinction
        // between FAILED and WARNING. Three of four premiums read as zero here.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "0.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "0.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "0.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.FAILED);
        assertThat(result.discrepancies()).hasSize(3);
        assertThat(result.summary()).contains("read incorrectly");
    }

    @Test
    void reportsNotApplicableWhenTheStatementHasNoRunningBalance() {
        // Plenty of real statements have no balance column. Claiming a verification that never
        // happened would be worse than saying so.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", null),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", null),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", null));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(result.rowsWithBalance()).isZero();
        assertThat(result.summary()).contains("no running-balance column");
    }

    @Test
    void withoutAnOpeningBalance_theFirstRowIsNeverChecked() {
        // Documents the limitation the anchor exists to remove: chaining consecutive pairs cannot
        // test the first row, because nothing precedes it. On the real statement this validator was
        // built for, the opening deposit is typed EXPENSE and the pair-only check still says
        // VERIFIED -- the error is in the one position the chain cannot see.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "EXPENSE", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"));

        assertThat(validator.validate(rows).status()).isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void anOpeningBalanceCatchesAWrongFirstRow() {
        // Same rows, now anchored. 0.00 + 25000 credited reaches 25000; recorded as an expense it
        // reaches -25000, so the first row finally has something to fail against.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "EXPENSE", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"));

        var result = validator.validate(rows, new BigDecimal("0.00"));

        assertThat(result.status()).isNotEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).rowIndex()).isZero();
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("50000.00");
    }

    @Test
    void anOpeningBalanceStillVerifiesACorrectStatement() {
        var result = validator.validate(correctlyParsedStatement(), new BigDecimal("0.00"));

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
        assertThat(result.rowsChecked()).isEqualTo(4);
    }

    @Test
    void handlesAnEmptyStatementWithoutFailing() {
        assertThat(validator.validate(List.of()).status()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(validator.validate(null).status()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void ignoresRowsWithNoBalance_ratherThanTreatingThemAsBreaks() {
        // A mid-statement row without its own balance (a summary line, a fee note) must not be
        // reported as a discrepancy -- the chain carries through it and continues.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "SOME NOTE", "0.00", "EXPENSE", null),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24898.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
    }

    /**
     * The case the test above could not distinguish, because its balance-less row carries an
     * amount of 0.00 and therefore moves nothing.
     *
     * <p>A balance-less row with a REAL amount used to be skipped outright, leaving the next
     * balance-bearing row checked against the balance from before it with only its own amount
     * added -- short by exactly the skipped amount, so a correct row was reported as a
     * discrepancy. The chain now carries the known amount forward instead, which is possible
     * precisely because only the BALANCE is missing; the amount is right there.
     */
    @Test
    void carriesTheChainThroughABalancelessRow_usingItsAmount() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "ATM FEE", "20.00", "EXPENSE", null),
                row("2026-07-16", "PMSBY PREMIUM", "80.00", "EXPENSE", "24900.00"),
                row("2026-07-18", "APY INSTALLMENT", "2.00", "EXPENSE", "24898.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).isEmpty();
    }

    /** Where the amount is ALSO missing, the row's effect is genuinely unknown, so the chain has
     *  to break rather than guess. The rows after it resume a fresh chain instead of being
     *  measured against a balance that no longer means anything. */
    @Test
    void breaksTheChain_whenARowHasNeitherBalanceNorAmount() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "UNREADABLE LINE", null, "EXPENSE", null),
                row("2026-07-16", "PMSBY PREMIUM", "80.00", "EXPENSE", "24900.00"),
                row("2026-07-18", "APY INSTALLMENT", "2.00", "EXPENSE", "24898.00"));

        var result = validator.validate(rows);

        // No false discrepancy against the row right after the gap, which is the whole point.
        assertThat(result.discrepancies()).isEmpty();
    }

    // --- The shape of a discrepancy: what can be said about it without any of its numbers ---
    //
    // The count that survives persistence ("2 rows disagreed") could not tell these failures apart,
    // and telling them apart on a customer's statement meant querying the staged rows by hand. Each
    // test below is one of the patterns that had to be distinguished, on synthetic rows.

    private Map<String, Object> balanceChainDetails(List<StagedRow> rows, String openingBalance) {
        var report = validator.report(rows, openingBalance == null ? null : new BigDecimal(openingBalance));
        return report.findings().get(0).details();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shapes(Map<String, Object> details) {
        return (List<Map<String, Object>>) details.get("discrepancyShapes");
    }

    @Test
    void aFlippedDirectionIsNamedAsOne() {
        // The salary recorded as money out: the gap is exactly twice the row's own amount.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "OPENING SPEND", "1000.00", "EXPENSE", "9000.00"),
                row("2026-07-11", "SALARY", "25000.00", "EXPENSE", "34000.00"),
                row("2026-07-12", "RENT", "5000.00", "EXPENSE", "29000.00")), null);

        assertThat(shapes(details)).hasSize(1);
        var shape = shapes(details).get(0);
        assertThat(shape.get("rowIndex")).isEqualTo(1);
        assertThat(shape.get("directionLooksFlipped")).isEqualTo(true);
        assertThat(shape.get("balanceDidNotMove")).isEqualTo(false);
        assertThat(shape.get("sameDayAsPrevious")).isEqualTo(false);
        assertThat(shape.get("sameDayAsNext")).isEqualTo(false);
        assertThat(shape.get("gapMatchesAmountOfRows")).isEqualTo(List.of());
        assertThat(details.get("gapsCancel"))
                .as("one discrepancy has nothing to cancel against")
                .isEqualTo(false);
    }

    @Test
    void aFlippedDirectionIsRecognisedTheOtherWayRoundToo() {
        // Money out recorded as money in. Same pattern, opposite sign -- a check written against
        // only the first direction would call this one unrecognised.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-11", "RENT", "5000.00", "INCOME", "20000.00"),
                row("2026-07-12", "APY INSTALLMENT", "82.00", "EXPENSE", "19918.00")), null);

        assertThat(shapes(details)).hasSize(1);
        assertThat(shapes(details).get(0).get("directionLooksFlipped")).isEqualTo(true);
    }

    @Test
    void aSlippedBalanceColumnShowsAsARepeatedBalanceAndACatchUpThatCancelIt() {
        // The balance column prints one row late through a run of identical same-day debits, then
        // catches up. Inside the run the slip is invisible -- every row moves the balance by the
        // same amount -- so exactly two rows disagree: the one that repeats the balance before it,
        // and the one where the column catches up.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "10000.00", "INCOME", "10000.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9500.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9500.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9000.00"),
                row("2026-07-16", "ATM FEE", "200.00", "EXPENSE", "8300.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "8218.00")), null);

        assertThat(shapes(details)).hasSize(2);

        var repeated = shapes(details).get(0);
        assertThat(repeated.get("rowIndex")).isEqualTo(2);
        assertThat(repeated.get("balanceDidNotMove")).isEqualTo(true);
        assertThat(repeated.get("directionLooksFlipped")).isEqualTo(false);
        assertThat(repeated.get("sameDayAsPrevious")).isEqualTo(true);
        assertThat(repeated.get("sameDayAsNext")).isEqualTo(true);
        assertThat(repeated.get("gapMatchesAmountOfRows"))
                .as("the gap is the amount its identical neighbours share, nearest first")
                .isEqualTo(List.of(1, 3));

        var catchUp = shapes(details).get(1);
        assertThat(catchUp.get("rowIndex")).isEqualTo(4);
        assertThat(catchUp.get("balanceDidNotMove")).isEqualTo(false);
        assertThat(catchUp.get("directionLooksFlipped")).isEqualTo(false);
        assertThat(catchUp.get("sameDayAsPrevious")).isEqualTo(true);
        assertThat(catchUp.get("sameDayAsNext")).isEqualTo(false);
        assertThat(catchUp.get("gapMatchesAmountOfRows")).isEqualTo(List.of(3, 2, 1));

        assertThat(details.get("gapsCancel"))
                .as("what one row is over, the other is short: the statement as a whole still adds up")
                .isEqualTo(true);
    }

    @Test
    void aSingleMisreadAmountMatchesNoPattern() {
        // 436.00 read as 463.00. The gap is no number the rows contain, so nothing can be said
        // about it beyond where it is -- and saying nothing is the correct description.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-17", "PMJJBY PREMIUM", "463.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00")), null);

        assertThat(shapes(details)).hasSize(1);
        var shape = shapes(details).get(0);
        assertThat(shape.get("rowIndex")).isEqualTo(2);
        assertThat(shape.get("directionLooksFlipped")).isEqualTo(false);
        assertThat(shape.get("balanceDidNotMove")).isEqualTo(false);
        assertThat(shape.get("gapMatchesAmountOfRows")).isEqualTo(List.of());
        assertThat(details.get("gapsCancel")).isEqualTo(false);
    }

    @Test
    void aCleanChainReportsExactlyWhatItAlwaysDid() {
        // Nothing disagreed, so there is nothing to describe -- and a statement that verifies must
        // not grow new fields in the response a customer's review screen is built from.
        var details = balanceChainDetails(correctlyParsedStatement(), "0.00");

        assertThat(details).containsOnlyKeys(
                "rowsChecked", "rowsWithBalance", "anchoredOnOpeningBalance", "discrepancies");
        assertThat((List<?>) details.get("discrepancies")).isEmpty();
    }

    @Test
    void aStatementTooShortToJudgeDescribesNothing() {
        // NOT_APPLICABLE drops its discrepancies on purpose (see MIN_PAIRS_FOR_A_VERDICT). A shape
        // for a discrepancy the verdict refused to report would be the one place it still showed.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00")), null);

        assertThat(details).doesNotContainKeys("discrepancyShapes", "gapsCancel");
    }

    @Test
    void aShapeHoldsOnlyPositionsAndYesNoAnswers() {
        // The property the whole description rests on, asserted on the values' TYPES rather than on
        // a list of numbers that must be absent: a description that has no decimal, no date and no
        // text in it cannot be carrying an amount, a balance, a transaction date or a narration.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "10000.00", "INCOME", "10000.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9500.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9500.00"),
                row("2026-07-16", "TRANSFER", "500.00", "EXPENSE", "9000.00"),
                row("2026-07-16", "ATM FEE", "200.00", "EXPENSE", "8300.00")), null);

        assertThat(shapes(details)).isNotEmpty().allSatisfy(shape -> {
            assertThat(shape).containsOnlyKeys("rowIndex", "sameDayAsPrevious", "sameDayAsNext",
                    "directionLooksFlipped", "balanceDidNotMove", "gapMatchesAmountOfRows");
            assertThat(shape.get("rowIndex")).isInstanceOf(Integer.class);
            assertThat(shape.get("gapMatchesAmountOfRows")).asInstanceOf(LIST)
                    .allSatisfy(position -> assertThat(position).isInstanceOf(Integer.class));
            assertThat(shape.entrySet())
                    .filteredOn(entry -> !entry.getKey().equals("rowIndex")
                            && !entry.getKey().equals("gapMatchesAmountOfRows"))
                    .allSatisfy(entry -> assertThat(entry.getValue()).isInstanceOf(Boolean.class));
        });
        assertThat(details.get("gapsCancel")).isInstanceOf(Boolean.class);
        assertThat(shapes(details).toString())
                .doesNotContain("500").doesNotContain("9500").doesNotContain("8300")
                .doesNotContain("TRANSFER").doesNotContain("2026");
    }

    @Test
    void aRepeatedBalanceIsJudgedAgainstWhatWasPrinted_notAgainstTheCarriedBalance() {
        // A balance-less row sits between the two printed balances. The chain carries its amount
        // forward, so the balance it EXPECTS has moved -- but the column printed the same number
        // twice, and that is what "did not move" is a statement about.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "ATM FEE", "20.00", "EXPENSE", null),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24980.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24898.00")), null);

        assertThat(shapes(details)).hasSize(1);
        assertThat(shapes(details).get(0).get("rowIndex")).isEqualTo(3);
        assertThat(shapes(details).get(0).get("balanceDidNotMove")).isEqualTo(true);
    }

    @Test
    void theFirstRowCanRepeatTheOpeningBalance() {
        // Anchored, the first row is an ordinary link, so it can fail in this way like any other --
        // and it has no previous row, which is the index a neighbour lookup gets wrong.
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "PMSBY PREMIUM", "20.00", "EXPENSE", "25000.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24564.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24482.00")), "25000.00");

        assertThat(shapes(details)).hasSize(1);
        var shape = shapes(details).get(0);
        assertThat(shape.get("rowIndex")).isEqualTo(0);
        assertThat(shape.get("balanceDidNotMove")).isEqualTo(true);
        assertThat(shape.get("sameDayAsPrevious")).isEqualTo(false);
    }

    @Test
    void theLastRowHasNoNextRowToShareADayWith() {
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "463.00", "EXPENSE", "24544.00")), null);

        assertThat(shapes(details)).hasSize(1);
        var shape = shapes(details).get(0);
        assertThat(shape.get("rowIndex")).isEqualTo(2);
        assertThat(shape.get("sameDayAsPrevious")).isEqualTo(true);
        assertThat(shape.get("sameDayAsNext")).isEqualTo(false);
    }

    @Test
    void rowsWithNoDateAreNeverCalledTheSameDay() {
        // Two missing dates are equal as values and say nothing about the calendar.
        var undated = new StagedRow(null, "PMJJBY PREMIUM", new BigDecimal("463.00"), "EXPENSE",
                "Other", "default", null, false, null, new BigDecimal("24544.00"));
        var alsoUndated = new StagedRow(null, "APY INSTALLMENT", new BigDecimal("82.00"), "EXPENSE",
                "Other", "default", null, false, null, new BigDecimal("24462.00"));
        var details = balanceChainDetails(List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                undated, alsoUndated), null);

        assertThat(shapes(details)).hasSize(1);
        assertThat(shapes(details).get(0).get("sameDayAsNext")).isEqualTo(false);
    }

    @Test
    void aWholeColumnMisreadIsDescribedForItsFirstRowsOnly_andStillCountedInFull() {
        // Every debit read as zero: each row disagrees and each looks like the last. Describing all
        // of them would say nothing the first few did not, in a response and a table row that grow
        // with the statement.
        List<StagedRow> rows = new java.util.ArrayList<>();
        rows.add(row("2026-07-01", "UPI CREDIT", "100000.00", "INCOME", "100000.00"));
        BigDecimal balance = new BigDecimal("100000.00");
        for (int i = 1; i <= 30; i++) {
            balance = balance.subtract(new BigDecimal(i));
            rows.add(row("2026-07-02", "DEBIT", "0.00", "EXPENSE", balance.toPlainString()));
        }

        var details = balanceChainDetails(rows, null);

        assertThat((List<?>) details.get("discrepancies")).hasSize(30);
        assertThat(shapes(details)).hasSize(BalanceChainValidator.MAX_DISCREPANCY_SHAPES);
        assertThat(shapes(details).get(0).get("rowIndex")).isEqualTo(1);
    }

    @Test
    void aGapIsMatchedAgainstItsNearestRowsOnly() {
        // Eight rows share the gap's amount. Naming all of them would be a list of every equal
        // amount in the statement; the nearest few are the ones that bear on this row.
        List<StagedRow> rows = new java.util.ArrayList<>();
        rows.add(row("2026-07-01", "UPI CREDIT", "10000.00", "INCOME", "10000.00"));
        BigDecimal balance = new BigDecimal("10000.00");
        for (int i = 0; i < 4; i++) {
            balance = balance.subtract(new BigDecimal("500.00"));
            rows.add(row("2026-07-02", "TRANSFER", "500.00", "EXPENSE", balance.toPlainString()));
        }
        // Repeats the balance before it.
        rows.add(row("2026-07-02", "TRANSFER", "500.00", "EXPENSE", balance.toPlainString()));
        for (int i = 0; i < 4; i++) {
            balance = balance.subtract(new BigDecimal("500.00"));
            rows.add(row("2026-07-02", "TRANSFER", "500.00", "EXPENSE", balance.toPlainString()));
        }

        var details = balanceChainDetails(rows, null);

        assertThat(shapes(details)).hasSize(1);
        assertThat(shapes(details).get(0).get("rowIndex")).isEqualTo(5);
        assertThat(shapes(details).get(0).get("gapMatchesAmountOfRows"))
                .isEqualTo(List.of(4, 6, 3, 7, 2));
    }

    @Test
    void aNewestFirstStatementIsDescribedAlongTheSameWalkItsDiscrepanciesAreNumberedBy() {
        // The validator walks a newest-first document oldest-first, and a discrepancy's rowIndex is
        // a position along that walk. A shape numbered any other way would point at a different row
        // from the discrepancy it describes.
        var rows = List.of(
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "463.00", "EXPENSE", "24544.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"));

        var discrepancies = validator.validate(rows).discrepancies();
        var details = balanceChainDetails(rows, null);

        assertThat(discrepancies).hasSize(1);
        assertThat(shapes(details)).hasSize(1);
        assertThat(shapes(details).get(0).get("rowIndex")).isEqualTo(discrepancies.get(0).rowIndex());
        assertThat(shapes(details).get(0).get("sameDayAsPrevious")).isEqualTo(true);
        assertThat(shapes(details).get(0).get("sameDayAsNext")).isEqualTo(false);
    }
}
