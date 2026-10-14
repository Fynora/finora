package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagedRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks parsed rows against the arithmetic the statement asserts about itself.
 *
 * <p><b>What this changes.</b> Everything upstream verifies that the parser produced <i>a</i>
 * result. Nothing verified it produced the <i>correct</i> one. Those are different guarantees, and
 * the gap between them is where silent wrongness lives: a real HDFC statement imported three
 * withdrawals as amount 0 and the fourth transaction in the wrong direction, and every stage
 * reported success, because nothing compared the numbers to anything.
 *
 * <p>Every statement already contains the check. Each row prints the balance after it, so each row
 * is an assertion:
 *
 * <pre>
 *   previousBalance + signedAmount == thisRow.balance
 * </pre>
 *
 * A wrong amount, a wrong direction, a dropped row and a duplicated row all break that identity
 * immediately. This is strictly stronger than checking parser heuristics against each other,
 * because it is the document's own arithmetic rather than our opinion of it.
 *
 * <p><b>Why per-row rather than a total.</b> A statement's printed debit/credit totals would tell
 * you "something is wrong". The chain tells you "row 17 is wrong, by 436.00". During support that
 * difference is most of the work — and it is the difference between a report someone can act on
 * and one they have to reproduce first.
 *
 * <p><b>Deliberately does not gate the import.</b> This classifies and reports; the caller decides.
 * A validator that refused an import would turn any false positive into "Finora cannot read my
 * statement", which is a worse failure than the one it prevents — and a statement can legitimately
 * defeat the chain (a mid-statement summary line, a reordered same-day pair, a bank that prints no
 * running balance at all). Refusing on that would be wrong. Being unable to say so would also be
 * wrong; hence three outcomes rather than a boolean.
 */
@Component
public class BalanceChainValidator {

    /**
     * How the parsed rows stand against the statement's own arithmetic.
     *
     * <p>{@link #NOT_APPLICABLE} is a first-class outcome, not a failure: plenty of real statements
     * carry no running-balance column, and reporting those as anything else would either cry wolf
     * or claim a verification that never happened.
     */
    public enum Outcome { VERIFIED, WARNING, FAILED, NOT_APPLICABLE }

    /**
     * One row whose recorded amount does not move the balance the way the statement says it did.
     *
     * <p>{@code difference} is what the row would have needed to be, minus what it was — so it
     * reads directly as "we are short by 436.00 here", which on the motivating statement is exactly
     * the premium that was parsed as zero.
     *
     * <p>{@code rowIndex} is the row's position in the list this validator was GIVEN. It used to be
     * the position along the walk, which is the same thing until the walk runs backwards: a
     * newest-first statement is reversed before it is checked, and every discrepancy on one then
     * named the mirror-image row -- row 2 of 6 for a fault on row 5. Anything holding the caller's
     * list can now index it with this number directly.
     */
    public record Discrepancy(
            int rowIndex,
            LocalDate date,
            String description,
            BigDecimal expectedBalance,
            BigDecimal actualBalance,
            BigDecimal difference
    ) {}

    /** The verdict, plus the evidence for it. */
    public record Result(Outcome status, List<Discrepancy> discrepancies, int rowsChecked, int rowsWithBalance) {

        public boolean isVerified() { return status == Outcome.VERIFIED; }

        /** One line for a human -- the import preview, a log, or a support conversation. */
        public String summary() {
            return switch (status) {
                case VERIFIED -> "Running balance verified across " + rowsChecked + " transaction(s).";
                case NOT_APPLICABLE -> "This statement has no running-balance column, so the amounts could not be cross-checked.";
                case WARNING -> discrepancies.size() + " of " + rowsChecked
                        + " transaction(s) don't match the statement's running balance. Review before importing.";
                case FAILED -> "The running balance doesn't reconcile for " + discrepancies.size()
                        + " of " + rowsChecked + " transaction(s). These amounts are probably being read incorrectly.";
            };
        }
    }

    /**
     * At least this many consecutive balance pairs must exist before a verdict means anything. One
     * pair is a coin flip -- a single mismatch could as easily be a mid-statement summary line as a
     * parsing fault, and reporting FAILED off one comparison would train people to ignore this.
     */
    private static final int MIN_PAIRS_FOR_A_VERDICT = 2;

    /**
     * At or above this share of checked rows failing, the cause is systematic rather than a quirk.
     *
     * <p>Half is chosen because the failure this exists to catch is a whole COLUMN being misread --
     * which breaks most rows, not a scattered few. Below that threshold the likelier explanation is
     * something about the specific document, which is worth surfacing but not worth calling broken.
     */
    private static final double FAILED_THRESHOLD = 0.5;

    /**
     * FAILED additionally requires this many bad rows, whatever the ratio says.
     *
     * <p>Found by the tests rather than reasoned out first: on a three-row statement there are only
     * two pairs, so a SINGLE bad row is 50% and was being reported as systematic. One discrepancy
     * is never systematic no matter how short the statement -- the whole-column misread this
     * distinction exists to name breaks several rows by definition. Small samples were making the
     * ratio say something it could not support.
     */
    private static final int MIN_DISCREPANCIES_FOR_FAILED = 2;

    /** The machine identifier this validator reports findings under. Stable by contract: clients
     *  group, count and explain by it, so it must not change when the wording does. */
    public static final String RULE = "BALANCE_CHAIN";

    /**
     * How many discrepancies get a shape. A whole column misread breaks hundreds of rows that all
     * look alike, and the twentieth identical line tells an operator nothing the first did not --
     * while a quirk of one document, the case shapes exist for, is a handful of rows by definition.
     * The full count is still reported, so a truncated list says that it is one.
     */
    static final int MAX_DISCREPANCY_SHAPES = 20;

    /** How many other rows a single gap is matched against by name. The nearest ones are the
     *  evidence (a slipped column, a dropped or repeated neighbour); a match forty rows away is
     *  two transactions that happen to share an amount. */
    static final int MAX_GAP_MATCH_ROWS = 5;

    /**
     * Runs the check and returns it in the shape the API exposes.
     *
     * <p>Kept here rather than in each producer so the two staging paths (CSV and PDF) cannot
     * describe the same finding differently -- the sort of drift this repository has repeatedly
     * had to go back and fix.
     *
     * <p>Returns a report holding exactly one finding, deliberately rather than the finding itself:
     * a second validator appends to the list and nothing about the shape changes, whereas returning
     * a bare finding would have to be reshaped the first time there were two.
     *
     * <h2>The shape of a discrepancy, as well as its numbers</h2>
     *
     * <p>{@code discrepancies} says how far each row is out, in the customer's own money. That is
     * what the person reviewing their import needs and exactly what must not outlive the request:
     * {@code ImportVerificationRecorder} reduces the list to a count before anything is persisted.
     * A count alone turned out not to be enough to act on. Telling a slipped balance column from a
     * flipped direction on a customer's statement meant querying the staged rows by hand, inside
     * the 48 hours before they are deleted.
     *
     * <p>{@code discrepancyShapes} carries the part of that diagnosis that is not statement
     * content. Every field is a position or a yes/no about how two of the statement's numbers
     * relate, never one of the numbers: where the row sits, whether its neighbours share its day,
     * whether the gap is twice its own amount, whether the printed balance repeats the one before
     * it, which nearby rows carry an amount equal to the gap. {@code gapsCancel} says whether the
     * gaps sum to nothing. No amount, balance, narration or date is in any of it, which is what
     * lets the recorder keep it.
     *
     * <p>Both are present only when something disagreed, so a statement that verifies reports
     * exactly what it did before these existed.
     */
    public ImportDto.VerificationReport report(List<StagedRow> rows, BigDecimal openingBalance) {
        return report(rows, openingBalance, null);
    }

    /**
     * As {@link #report(List, BigDecimal)}, for a caller that shows the rows in a different order
     * from the one it has them checked in.
     *
     * <p><b>Every row number in the finding is a position in the rows as DISPLAYED.</b> The number
     * exists to be counted to: the review screen prints it as "Row 13" beside a table of rows, and
     * the admin trace prints the same number so that a support conversation and an operator are
     * talking about the same line. It is only worth printing if it is the thirteenth row on that
     * screen.
     *
     * <p>It was not, in two ways that were measured on synthetic statements through both staging
     * paths. The walk reverses a newest-first statement, and the number was a position along the
     * walk, so on a newest-first CSV every row but the middle one was named by its mirror image.
     * And the PDF path checks the document's own sequence while showing a date-sorted copy, so a
     * row inside a same-day group of a newest-first statement, or any row whose date is out of
     * sequence, was numbered by where it sat in a list the customer never sees.
     *
     * <p>The chain still has to be walked in the document's sequence -- see {@link #validate(List)}
     * -- so the caller keeps handing over that sequence and says where each row ends up. Which
     * neighbours share a row's day, and which nearby rows match its gap, are likewise read off the
     * displayed order, because "the rows before and after" means the ones above and below it there.
     *
     * @param displayedPositionOf for each row in {@code rows}, its zero-based position in the list
     *                            the client is given; null when the two orders are the same
     * @throws IllegalArgumentException if it is not a rearrangement of exactly these rows -- a
     *                                  number that might point anywhere is the defect this exists
     *                                  to remove, so it is refused rather than guessed at
     */
    public ImportDto.VerificationReport report(List<StagedRow> rows, BigDecimal openingBalance,
                                                int[] displayedPositionOf) {
        // Checked before anything is numbered by it, and whether or not anything disagreed: a wrong
        // mapping that only surfaced on the statement with a discrepancy would be found by a
        // customer.
        List<StagedRow> shown = rows == null ? List.of() : displayed(rows, displayedPositionOf);
        Walk walk = walk(rows, openingBalance);
        Result result = walk.result();

        List<Located> located = locate(walk, displayedPositionOf);

        // Typed while it is being built, then handed over as the finding's per-rule payload. The
        // wire format does not name this shape, because the next checks planned (statement totals,
        // structural) have no row to point at -- see VerificationFinding.details.
        List<ImportDto.RowDiscrepancy> discrepancies = located.stream()
                // The displayed position twice: once under the name every reader so far uses, once
                // under the name only a report numbered this way carries -- see RowDiscrepancy.
                .map(l -> new ImportDto.RowDiscrepancy(l.displayedIndex(), l.displayedIndex(),
                        l.discrepancy().expectedBalance(), l.discrepancy().actualBalance(),
                        l.discrepancy().difference()))
                .toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", result.rowsChecked());
        details.put("rowsWithBalance", result.rowsWithBalance());
        details.put("anchoredOnOpeningBalance", openingBalance != null);
        details.put("discrepancies", discrepancies);
        if (!discrepancies.isEmpty()) {
            details.put("discrepancyShapes", shapesOf(shown, located));
            details.put("gapsCancel", gapsCancel(result.discrepancies()));
        }

        // The rule's verdict about its own domain, keeping the WARNING/FAILED distinction rather
        // than flattening to a boolean -- "a few rows disagree" and "this column is being misread"
        // call for different responses, and only this class knows which it saw.
        var finding = new ImportDto.VerificationFinding(RULE, result.status().name(), details);
        return new ImportDto.VerificationReport(List.of(finding));
    }

    /**
     * Validates rows in the order the statement presents them.
     *
     * <p>Order matters and is the caller's responsibility: the chain is only meaningful along the
     * document's own sequence, which for same-day transactions is not date order. That is exactly
     * what {@link BalanceChainUtil} exists to reconstruct, so rows arrive here already sequenced.
     */
    public Result validate(List<StagedRow> rows) {
        return validate(rows, null);
    }

    /**
     * As {@link #validate(List)}, but anchored on the statement's printed opening balance.
     *
     * <p><b>This is not a refinement, it closes a hole.</b> Chaining consecutive pairs never tests
     * the FIRST row -- there is nothing before it to chain from -- so a first row with the wrong
     * amount or the wrong direction passes silently while every later row verifies. That is not
     * hypothetical: on the statement that motivated this validator, the opening deposit is typed
     * as an expense and the pair-only check reports VERIFIED, because the error sits in the one
     * position the chain cannot see.
     *
     * <p>An opening balance turns the first row into an ordinary link. Pass null when the document
     * did not state one, or when what was detected is not trustworthy -- a WRONG anchor would
     * report a discrepancy on a row that is actually correct, which is the one outcome that would
     * make people stop believing this check.
     */
    public Result validate(List<StagedRow> rows, BigDecimal openingBalance) {
        return walk(rows, openingBalance).result();
    }

    /**
     * The verdict, together with the one fact about each discrepancy that only the walk knows: the
     * balance last PRINTED before it, index-aligned with {@code result.discrepancies()}.
     *
     * <p>Not the running balance: that one has the amounts of any balance-less rows folded in, and
     * "the balance column repeated itself" is a statement about what the document printed. Private
     * because only {@link #report} describes a discrepancy; everything else that consults this
     * validator wants the verdict and reads {@link Result}.
     */
    private record Walk(Result result, List<BigDecimal> printedBefore) {
        static Walk of(Result result) {
            return new Walk(result, List.of());
        }
    }

    /** A discrepancy, placed where the client will show its row. */
    private record Located(int displayedIndex, Discrepancy discrepancy, BigDecimal printedBefore) {}

    private Walk walk(List<StagedRow> given, BigDecimal openingBalance) {
        if (given == null || given.isEmpty()) {
            return Walk.of(new Result(Outcome.NOT_APPLICABLE, List.of(), 0, 0));
        }

        List<StagedRow> rows = chronological(given);
        // Identity, not equals: chronological() hands back the same list when it did not reverse.
        boolean reversed = rows != given;

        int rowsWithBalance = (int) rows.stream().filter(r -> r.balanceAfter() != null).count();

        List<Discrepancy> discrepancies = new ArrayList<>();
        List<BigDecimal> printedBefore = new ArrayList<>();
        int pairsChecked = 0;

        // One running balance, rather than a "previous row" plus a separate opening anchor.
        //
        // Bug fix: a row with no printed balance used to `continue` without touching the anchor,
        // so the next balance-bearing row was checked against the last one BEFORE the gap with
        // only its own amount added. The skipped row's effect on the balance was never included,
        // so `expected` came up short by exactly that amount and a discrepancy was recorded
        // against a row that was entirely correct. One balance-less row mid-statement produced one
        // spurious discrepancy; enough of them reached FAILED on a clean import. Reachable on the
        // PDF path in particular, where PdfTableLocator "treats everything after a header as a
        // candidate row", so a fee line or a carried-forward line normalizes into a StagedRow with
        // no balance and sits mid-chain.
        //
        // Carrying the balance forward is strictly better than either ignoring the row (the bug)
        // or breaking the chain at it (the obvious fix). A row missing only its BALANCE still has
        // an amount, so its effect is known exactly and the chain can continue through it with
        // full accuracy -- which is what keeps a zero-amount note line from costing a verdict, and
        // what makes a non-zero one stop lying. The chain only breaks where the AMOUNT is unknown,
        // because that is the one case where the running balance genuinely cannot be carried.
        // null means "unknown from here", so the next row is skipped rather than checked against a
        // guess -- NOT_APPLICABLE and rowsChecked already exist as the vocabulary for that.
        BigDecimal runningBalance = openingBalance;
        BigDecimal lastPrinted = openingBalance;

        for (int i = 0; i < rows.size(); i++) {
            StagedRow row = rows.get(i);

            if (row.amount() == null) {
                runningBalance = null;
                lastPrinted = null;
                continue;
            }

            if (row.balanceAfter() == null) {
                if (runningBalance != null) runningBalance = runningBalance.add(signedAmount(row));
                continue;
            }

            if (runningBalance != null) {
                BigDecimal expected = runningBalance.add(signedAmount(row));
                if (expected.compareTo(row.balanceAfter()) != 0) {
                    // Where the row sits in the list the caller handed over, not along this walk --
                    // see Discrepancy.rowIndex.
                    int givenIndex = reversed ? rows.size() - 1 - i : i;
                    discrepancies.add(new Discrepancy(
                            givenIndex, row.date(), row.description(),
                            expected, row.balanceAfter(),
                            row.balanceAfter().subtract(expected)));
                    printedBefore.add(lastPrinted);
                }
                pairsChecked++;
            }
            // The statement's own printed balance wins over the computed one from here: a single
            // misread row should cost one discrepancy, not cascade into every row after it.
            runningBalance = row.balanceAfter();
            lastPrinted = row.balanceAfter();
        }

        if (pairsChecked < MIN_PAIRS_FOR_A_VERDICT) {
            return Walk.of(new Result(Outcome.NOT_APPLICABLE, List.of(), pairsChecked, rowsWithBalance));
        }
        if (discrepancies.isEmpty()) {
            return Walk.of(new Result(Outcome.VERIFIED, List.of(), pairsChecked, rowsWithBalance));
        }

        boolean systematic = discrepancies.size() >= MIN_DISCREPANCIES_FOR_FAILED
                && (double) discrepancies.size() / pairsChecked >= FAILED_THRESHOLD;
        Outcome status = systematic ? Outcome.FAILED : Outcome.WARNING;
        return new Walk(new Result(status, List.copyOf(discrepancies), pairsChecked, rowsWithBalance),
                java.util.Collections.unmodifiableList(printedBefore));
    }

    /**
     * Places each discrepancy at its row's displayed position, in the order a reader meets them.
     *
     * <p>Sorted by that position because the walk's own order is not one anybody reads in: on a
     * newest-first statement it runs up the screen, and after a date sort it need not run in either
     * direction. The review table lists them top to bottom, and "the first 20" has to mean the
     * first 20 down the page.
     */
    private static List<Located> locate(Walk walk, int[] displayedPositionOf) {
        List<Discrepancy> discrepancies = walk.result().discrepancies();
        List<Located> located = new ArrayList<>(discrepancies.size());
        for (int n = 0; n < discrepancies.size(); n++) {
            Discrepancy discrepancy = discrepancies.get(n);
            int displayedIndex = displayedPositionOf == null
                    ? discrepancy.rowIndex() : displayedPositionOf[discrepancy.rowIndex()];
            located.add(new Located(displayedIndex, discrepancy, walk.printedBefore().get(n)));
        }
        located.sort(java.util.Comparator.comparingInt(Located::displayedIndex));
        return located;
    }

    /**
     * The rows in the order the client shows them.
     *
     * <p>Refuses anything that is not a rearrangement of exactly the given rows. Two rows sent to
     * one position, or a position past the end, would still produce a number for every discrepancy
     * -- a confident one, pointing at the wrong row or at none.
     */
    private static List<StagedRow> displayed(List<StagedRow> given, int[] displayedPositionOf) {
        if (displayedPositionOf == null) return given;
        if (displayedPositionOf.length != given.size()) {
            throw new IllegalArgumentException("displayedPositionOf names " + displayedPositionOf.length
                    + " row(s) but " + given.size() + " were checked");
        }
        StagedRow[] displayed = new StagedRow[given.size()];
        boolean[] taken = new boolean[given.size()];
        for (int i = 0; i < displayedPositionOf.length; i++) {
            int position = displayedPositionOf[i];
            if (position < 0 || position >= displayed.length || taken[position]) {
                throw new IllegalArgumentException("displayedPositionOf is not a rearrangement of the "
                        + "checked rows: position " + position + " is out of range or used twice");
            }
            taken[position] = true;
            displayed[position] = given.get(i);
        }
        return java.util.Arrays.asList(displayed);
    }

    /**
     * Describes each discrepancy by how its numbers relate, without any of the numbers.
     *
     * <p>Built key by key into plain maps, not by projecting {@link Discrepancy}: that record holds
     * a date, a narration and three balances, and a description produced by leaving fields out of
     * it would be one added field away from leaking. {@code rowIndex} is the same displayed
     * position the discrepancy reports, so "row 13" here is the row 13 the customer was shown, and
     * its neighbours are the rows shown above and below it.
     *
     * <p>What each yes/no is evidence of, kept here because the names are read on their own:
     * <ul>
     *   <li>{@code directionLooksFlipped} -- the gap is exactly twice the row's own amount, the
     *       wrong way round. A credit read as a debit, or the reverse.</li>
     *   <li>{@code balanceDidNotMove} -- the printed balance is the one printed before it. A
     *       balance column that slipped a row, or the same row extracted twice.</li>
     *   <li>{@code gapMatchesAmountOfRows} -- the gap is exactly another row's amount. A slipped
     *       column catching up, or a neighbour's amount landing on the wrong row.</li>
     *   <li>None of them -- the gap is no number the staged rows contain: an amount misread, or a
     *       row that never arrived.</li>
     * </ul>
     * These are the patterns the facts are consistent with, not a diagnosis. Nothing here decides
     * which one happened; the operator reading it does.
     */
    private static List<Map<String, Object>> shapesOf(List<StagedRow> rows, List<Located> located) {
        List<Map<String, Object>> shapes = new ArrayList<>();
        int described = Math.min(located.size(), MAX_DISCREPANCY_SHAPES);
        for (int n = 0; n < described; n++) {
            Discrepancy discrepancy = located.get(n).discrepancy();
            int index = located.get(n).displayedIndex();
            StagedRow row = rows.get(index);
            BigDecimal signed = signedAmount(row);
            BigDecimal before = located.get(n).printedBefore();

            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("rowIndex", index);
            shape.put("sameDayAsPrevious", index > 0 && sameDay(rows.get(index - 1), row));
            shape.put("sameDayAsNext", index < rows.size() - 1 && sameDay(row, rows.get(index + 1)));
            shape.put("directionLooksFlipped", signed.signum() != 0
                    && discrepancy.difference().compareTo(signed.multiply(BigDecimal.valueOf(-2))) == 0);
            shape.put("balanceDidNotMove", before != null && row.balanceAfter().compareTo(before) == 0);
            shape.put("gapMatchesAmountOfRows", rowsWhoseAmountIs(discrepancy.difference().abs(), rows, index));
            shapes.add(java.util.Collections.unmodifiableMap(shape));
        }
        return List.copyOf(shapes);
    }

    /** The rows other than {@code index} whose amount equals {@code gap}, nearest first, bounded. */
    private static List<Integer> rowsWhoseAmountIs(BigDecimal gap, List<StagedRow> rows, int index) {
        List<Integer> matches = new ArrayList<>();
        for (int offset = 1; offset < rows.size() && matches.size() < MAX_GAP_MATCH_ROWS; offset++) {
            for (int candidate : new int[] {index - offset, index + offset}) {
                if (candidate < 0 || candidate >= rows.size() || matches.size() >= MAX_GAP_MATCH_ROWS) continue;
                BigDecimal amount = rows.get(candidate).amount();
                if (amount != null && amount.abs().compareTo(gap) == 0) matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Whether the gaps sum to nothing, over every discrepancy rather than only the described ones.
     *
     * <p>True means the rows disagree with the balance column and the statement as a whole does
     * not: whatever one row is short, another is over. That is the signature of a balance printed
     * against the wrong row, as opposed to money that was never read. One discrepancy cannot cancel
     * with anything, so it is never true for one.
     */
    private static boolean gapsCancel(List<Discrepancy> discrepancies) {
        if (discrepancies.size() < 2) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (Discrepancy discrepancy : discrepancies) sum = sum.add(discrepancy.difference());
        return sum.signum() == 0;
    }

    private static boolean sameDay(StagedRow a, StagedRow b) {
        return a.date() != null && a.date().equals(b.date());
    }

    /**
     * Returns {@code rows} in the direction the chain needs to walk forward through: whichever end
     * is chronologically earliest comes first.
     *
     * <p>The caller's contract (see {@link #validate(List)}) is "already sequenced along the
     * document's own order", not "sorted ascending" -- a full date sort would scramble same-day
     * clusters that {@link BalanceChainUtil} exists specifically to keep in their real chain order.
     * But some real exports (PNB ONE, confirmed on a live statement) print newest-first for the
     * ENTIRE document, not just within a day, and this validator's loop always treats the row
     * before position i as the one whose balance position i's amount was applied to. Fed a
     * newest-first document unchanged, every single pair is compared against the wrong
     * predecessor -- not a few rows, all of them, which is indistinguishable from "every amount is
     * misread" without this check.
     *
     * <p>Reversing (not sorting) is what preserves the same-day chain order a sort would destroy:
     * it only flips which end the walk starts from, so a same-day cluster's internal order --
     * whatever it truly is -- comes out the same relative sequence, just traversed the other way.
     * Detected from the two ends only, deliberately: a full ascending/descending vote over every
     * adjacent pair would also flag statements with an interior mid-statement summary line or a
     * same-day tie as "not clearly ordered" and skip the fix on exactly the documents most likely
     * to need it.
     */
    private static List<StagedRow> chronological(List<StagedRow> rows) {
        LocalDate first = rows.get(0).date();
        LocalDate last = rows.get(rows.size() - 1).date();
        if (first == null || last == null || !first.isAfter(last)) {
            return rows;
        }
        List<StagedRow> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * The amount as it moves the balance: negative for money out, positive for money in.
     *
     * <p>Reads the normalized type rather than the sign, because {@code StagedRow.amount} is
     * already absolute by the time it gets here (see TransactionNormalizer's {@code .abs()}). That
     * is what makes this check sensitive to DIRECTION errors and not just magnitude ones -- a
     * deposit recorded as an expense moves the balance the wrong way and shows up here as double
     * the amount, which is a far louder signal than being merely absent.
     */
    private static BigDecimal signedAmount(StagedRow row) {
        BigDecimal magnitude = row.amount().abs();
        return "INCOME".equals(row.type()) ? magnitude : magnitude.negate();
    }
}
