package com.finora.imports;

import com.finora.dto.ImportDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports rows whose raw columns admitted more than one reading, so a guess is visible as a guess.
 *
 * <p><b>This is the only rule that looks at the document rather than the result.</b> The other
 * three compare numbers that have already been decided: by the time a balance chain or a total can
 * object, an ambiguous cell has long since been resolved one way, and the parser's choice has
 * become the thing being verified. That is precisely the failure this exists for. A real statement
 * produced a cell reading {@code "0.00 25,000.00"} in its deposits column with nothing in
 * withdrawals; every downstream number was then internally consistent with the wrong answer.
 *
 * <p><b>It reports ambiguity, not error.</b> A guess flagged here may well be right — most are.
 * The claim is only that the document did not say, and that a person reviewing the import should
 * look at this row rather than assume it was determined. So the outcome is a warning even when
 * many rows are affected: escalating on volume would imply a confidence about which reading is
 * correct that this rule, by construction, does not have.
 *
 * <p><b>Deliberately does not re-run the parser's direction cascade.</b> {@link
 * TransactionNormalizer} decides direction from a Type column, then a credit column, then a
 * trailing Dr/Cr marker, then a leading plus, and finally defaults to an expense when nothing said
 * otherwise. That last default is also a guess, and a fair thing to want reported — but detecting
 * it here would mean duplicating the whole cascade, and a copy that drifts would report ambiguity
 * on rows the parser read confidently, or stay silent on rows it did not. What this checks instead
 * is decidable from the row alone: a cell that holds two amounts, and two direction columns that
 * both claim the money. Reporting the silent default belongs with the code that performs it.
 */
@Component
public class ColumnAmbiguityValidator {

    /** Stable machine identifier — clients group and explain by it, so it must not track wording. */
    public static final String RULE = "COLUMN_AMBIGUITY";

    /** A number as a statement prints one, with or without grouping and decimals. */
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private static final List<String> CREDIT_HINTS =
            List.of("deposit", "deposits", "credit", "credits", "deposit amount", "credit amount",
                    "cr amount", "deposit amt", "credit amt");
    private static final List<String> DEBIT_HINTS =
            List.of("withdrawal", "withdrawals", "debit", "debits", "withdrawal amount", "debit amount",
                    "dr amount", "withdrawal amt", "debit amt");

    /** A located row that did not become one of the rows the client is shown. */
    public static final int NOT_SHOWN = -1;

    /** Detail key naming the list each {@code rowIndex} counts along; {@link #ROWS_SHOWN} is the
     *  only value. Absent from a finding stored before the numbers meant the rows shown, and from
     *  one whose caller passed no display mapping, whose numbers count the located rows. */
    public static final String ROW_INDEX_BASIS = "rowIndexBasis";
    public static final String ROWS_SHOWN = "ROWS_SHOWN";

    /**
     * One row that could be read more than one way, and how.
     *
     * @param rowIndex the row's zero-based place among the rows the client is shown, which is the
     *                 number a person counts to; null when the row is not among them
     */
    private record Ambiguity(Integer rowIndex, String kind, String column, String value) {
        Map<String, Object> toDetails() {
            Map<String, Object> m = new LinkedHashMap<>();
            // Left out rather than sent as a stand-in. The client prints this number as "Row N"
            // beside a table of rows, so any number here names one of them, and a row that is not
            // in the table must not be given another row's.
            if (rowIndex != null) m.put("rowIndex", rowIndex);
            m.put("kind", kind);
            m.put("column", column);
            m.put("value", value);
            return m;
        }
    }

    public ImportDto.VerificationFinding check(List<Map<String, String>> rawRows) {
        return check(rawRows, List.of());
    }

    /**
     * @param unparseableRows rows that failed normalisation. Nothing was read from one, so no
     *                        reading was chosen and there is no guess to report: a real scanned
     *                        HSBC statement's footer ("... Balance Carried Forward") carries an
     *                        OCR'd date and several numbers in its withdrawals cell, was set aside
     *                        unread, and still raised this warning on a statement whose every
     *                        transaction was correct.
     */
    public ImportDto.VerificationFinding check(List<Map<String, String>> rawRows,
                                               List<ImportDto.UnparseableRow> unparseableRows) {
        return check(rawRows, unparseableRows, null);
    }

    /**
     * Names each ambiguous row by where the client shows it.
     *
     * <p>This rule reads the located rows, and the client shows the staged ones, which are a
     * different list twice over. Not every located row stages: a statement's own opening-balance
     * line and a row whose amount could not be read are both located and neither is shown, so every
     * row printed after one sits one place earlier on screen than it did in the document. And the
     * staged rows are shown sorted by date, so a newest-first statement or a back-dated entry moves
     * them again. The index used to be the position among the located rows, which is the displayed
     * position only when every located row staged and the dates were already in order. Measured
     * through the real PDF path before the fix, 6 of 10 synthetic statements named a row other than
     * the ambiguous one.
     *
     * @param displayedPositionOf for each row in {@code rawRows}, its zero-based position in the
     *                            list the client is given, or {@link #NOT_SHOWN} for a row that did
     *                            not stage; null when the client is shown exactly these rows in
     *                            this order
     * @throws IllegalArgumentException if it does not describe exactly these rows, or sends two of
     *                                  them to one position -- a number that might point anywhere
     *                                  is the defect this exists to remove, so it is refused rather
     *                                  than guessed at
     */
    public ImportDto.VerificationFinding check(List<Map<String, String>> rawRows,
                                               List<ImportDto.UnparseableRow> unparseableRows,
                                               int[] displayedPositionOf) {
        requireOnePositionPerRow(rawRows, displayedPositionOf);
        Map<String, Object> details = new LinkedHashMap<>();
        // By value: two rows with identical cells always normalise the same way, so if one was set
        // aside, so was the other.
        List<Map<String, String>> setAside = new ArrayList<>();
        if (unparseableRows != null) {
            for (ImportDto.UnparseableRow u : unparseableRows) {
                if (u != null && u.raw() != null) setAside.add(u.raw());
            }
        }

        if (rawRows == null || rawRows.isEmpty()) {
            details.put("reason", "The rows were not available in their original column form, so "
                    + "there was nothing to check for ambiguity.");
            return new ImportDto.VerificationFinding(RULE, "NOT_APPLICABLE", details);
        }

        List<Ambiguity> found = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, String> row = rawRows.get(i);
            if (row == null) continue;
            // Only rows that are actually transactions. The located rows include everything after
            // the header, boilerplate included, and a statement's trailing summary block routinely
            // lands in a direction column as something like "Credit Amount 538.00 25,000.00 Credit
            // Count 3 1" -- several amounts in one cell, and not remotely ambiguous, because it was
            // never a transaction. Found by running this against a real statement that parses
            // perfectly and watching it report a warning anyway. Mirrors TransactionNormalizer's
            // own gate: no date, no transaction.
            if (!looksLikeATransaction(row)) continue;
            // Only rows something was read from: see the unparseableRows parameter above.
            if (setAside.contains(row)) continue;

            Integer shownAt = displayedPositionOf == null ? Integer.valueOf(i)
                    : displayedPositionOf[i] == NOT_SHOWN ? null : Integer.valueOf(displayedPositionOf[i]);
            String creditColumn = null, debitColumn = null;
            BigDecimal credit = null, debit = null;

            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String column = CsvParser.normalizeHeaderCell(e.getKey()).toLowerCase(Locale.ROOT);
                String value = e.getValue().trim();
                boolean isCredit = CREDIT_HINTS.contains(column);
                boolean isDebit = DEBIT_HINTS.contains(column);
                if (!isCredit && !isDebit) continue;

                // Two amounts in one cell. Whichever the parser picked, the other had an equal
                // claim -- this is the shape that made a 25,000 deposit read as an expense.
                if (countAmounts(value) > 1) {
                    found.add(new Ambiguity(shownAt, "MULTIPLE_AMOUNTS_IN_ONE_COLUMN", e.getKey(), value));
                    continue;
                }

                BigDecimal parsed = CsvParser.parseNumeric(value);
                if (parsed == null || parsed.signum() == 0) continue;
                if (isCredit) { creditColumn = e.getKey(); credit = parsed; }
                else { debitColumn = e.getKey(); debit = parsed; }
            }

            // Both sides claiming money on one row. A separate-columns layout prints a zero in the
            // side that did not move, so two non-zero values are a contradiction rather than a
            // layout quirk -- and the row can be read as either a credit or a debit.
            if (credit != null && debit != null) {
                found.add(new Ambiguity(shownAt, "BOTH_DIRECTIONS_HAVE_A_VALUE",
                        creditColumn + " / " + debitColumn, credit + " / " + debit));
            }
        }
        // Down the page, in the order the rows are shown: the list is read beside the review table,
        // and after a date sort the document's own order need not run in either direction along it.
        // Rows that are not shown go last. The sort is stable, so two findings on one row keep the
        // order their columns were read in.
        found.sort(Comparator.comparing(Ambiguity::rowIndex, Comparator.nullsLast(Comparator.naturalOrder())));

        details.put("rowsChecked", rawRows.size());
        details.put("ambiguousRows", found.size());

        if (found.isEmpty()) {
            return new ImportDto.VerificationFinding(RULE, "VERIFIED", details);
        }

        details.put("ambiguities", found.stream().map(Ambiguity::toDetails).toList());
        // Says which list the numbers count along. A review session keeps its report for days, and
        // one stored before this marker existed numbers along the located rows -- see
        // renumberStored, which tells the two apart by this key alone. Only claimed when the caller
        // said where each row is shown: without that the numbers are places among the located
        // rows, which is exactly what renumberStored converts, so leaving the marker off keeps a
        // report from a caller that passed no mapping correct once it is stored and read back.
        if (displayedPositionOf != null) details.put(ROW_INDEX_BASIS, ROWS_SHOWN);
        details.put("explanation", found.size() == 1
                ? "One row could be read more than one way. The import used its best reading, but the "
                  + "statement did not settle it — worth checking that row."
                : found.size() + " rows could be read more than one way. The import used its best "
                  + "reading of each, but the statement did not settle them — worth checking them.");
        return new ImportDto.VerificationFinding(RULE, "WARNING", details);
    }

    /**
     * Brings a finding stored by an earlier build onto the numbering the client now prints.
     *
     * <p>A review session stores its verification report when the statement is staged and serves it
     * again when the customer comes back to the review, for up to two days and longer while the
     * import is held. A report stored before {@link #ROW_INDEX_BASIS} existed numbers each ambiguous
     * row by its place among the located rows, and printing that number now would name another row
     * in exactly the cases the fix was for.
     *
     * <p>That old number converts exactly. The PDF generator stamps every staged row with {@code
     * rowPosition}, its 1-based place among the same located rows, so the row an old {@code
     * rowIndex} meant is the shown row whose {@code rowPosition} is one more. A number with no such
     * row -- the row did not stage, or the stored rows carry no positions -- is left out rather
     * than guessed, the same as a fresh finding does for a row that is not shown. Anything that is
     * not an old ambiguity list is returned as it came.
     *
     * @param shown the session's stored rows, in the order the client shows them
     */
    public static ImportDto.VerificationFinding renumberStored(ImportDto.VerificationFinding finding,
                                                               List<ImportDto.StagedRow> shown) {
        if (finding == null || !RULE.equals(finding.rule()) || finding.details() == null) return finding;
        if (finding.details().containsKey(ROW_INDEX_BASIS)) return finding;
        if (!(finding.details().get("ambiguities") instanceof List<?> stored)) return finding;

        Map<Integer, Integer> shownAtByPosition = new java.util.HashMap<>();
        for (int k = 0; shown != null && k < shown.size(); k++) {
            Integer rowPosition = shown.get(k) == null ? null : shown.get(k).rowPosition();
            if (rowPosition != null) shownAtByPosition.putIfAbsent(rowPosition, k);
        }

        List<Map<String, Object>> renumbered = new ArrayList<>();
        for (Object entry : stored) {
            if (!(entry instanceof Map<?, ?> old)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            old.forEach((key, value) -> copy.put(String.valueOf(key), value));
            Integer shownAt = copy.remove("rowIndex") instanceof Number located
                    ? shownAtByPosition.get(located.intValue() + 1) : null;
            if (shownAt != null) copy.put("rowIndex", shownAt);
            renumbered.add(copy);
        }
        renumbered.sort(Comparator.comparing(m -> (Integer) m.get("rowIndex"),
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Object> details = new LinkedHashMap<>();
        finding.details().forEach((key, value) -> details.put(key, "ambiguities".equals(key) ? renumbered : value));
        details.put(ROW_INDEX_BASIS, ROWS_SHOWN);
        return new ImportDto.VerificationFinding(finding.rule(), finding.outcome(), details);
    }

    /**
     * Refuses a mapping that is not one position per located row, or that shows two rows in one
     * place. Checked before anything is numbered by it, and whether or not anything is ambiguous:
     * a wrong mapping that only surfaced on the statement with a finding would be found by a
     * customer.
     */
    private static void requireOnePositionPerRow(List<Map<String, String>> rawRows, int[] displayedPositionOf) {
        if (displayedPositionOf == null) return;
        int located = rawRows == null ? 0 : rawRows.size();
        if (displayedPositionOf.length != located) {
            throw new IllegalArgumentException("displayedPositionOf names " + displayedPositionOf.length
                    + " row(s) but " + located + " were located");
        }
        boolean[] taken = new boolean[located];
        for (int position : displayedPositionOf) {
            if (position == NOT_SHOWN) continue;
            // No more rows can be shown than were located, so a position at or past that count is
            // not one of them.
            if (position < 0 || position >= located || taken[position]) {
                throw new IllegalArgumentException("displayedPositionOf is not a placement of the located "
                        + "rows: position " + position + " is out of range or used twice");
            }
            taken[position] = true;
        }
    }

    /** Checked by value rather than by column name: a summary block's text lands in whichever
     *  column its x position happens to fall under, so trusting the column heading here would be
     *  trusting the very bucketing that is under suspicion. */
    private static boolean looksLikeATransaction(Map<String, String> row) {
        return row.values().stream()
                .anyMatch(v -> v != null && CsvParser.parseDate(v.trim()) != null);
    }

    private static int countAmounts(String value) {
        Matcher m = AMOUNT.matcher(value);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
