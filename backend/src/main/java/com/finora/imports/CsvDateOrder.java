package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;

import java.util.regex.Matcher;

/**
 * Decides, once per CSV file, whether its numeric dates put the day or the month first.
 *
 * <p>Each cell used to be read on its own: day-first was tried, then month-first. So a month-first
 * file whose dates all fit both readings ({@code 07/01/2026 ... 07/09/2026}) staged as 7 January to
 * 7 September, every check passed, and it imported with the wrong dates. A file that mixed
 * {@code 01/05} with {@code 01/13} read some rows one way and some the other.
 *
 * <p>Now the whole date column is read first. Sid's decision (2026-10-24, option B):
 * <ul>
 *   <li>A date whose first number is over 12 proves day-first; one whose second number is over 12
 *       proves month-first.</li>
 *   <li>Both proofs in one file is a contradiction, and the file is refused
 *       ({@link ErrorCode#IMPORT_DATE_ORDER_CONFLICT}): no single reading fits every row.</li>
 *   <li>No proof either way: day-first is used, because no statement in the real corpus puts the
 *       month first (measured over 37 statements and 22 more loose files: every file with numeric
 *       dates had day-first proof, none had month-first proof). But when the two readings would
 *       give different dates, {@link ColumnAmbiguityValidator} reports those rows, and the trust
 *       gate holds the file for review instead of importing a guess.</li>
 * </ul>
 *
 * <p>CSV only. The PDF path keeps {@link CsvParser#parseDate(String)}, whose per-cell behaviour the
 * PDF table locator depends on to decide where a row starts.
 */
public final class CsvDateOrder {

    public enum Order { DAY_FIRST, MONTH_FIRST }

    /**
     * @param order    the reading every numeric date in the file is parsed with
     * @param proven   whether some date in the file settled the order
     */
    public record Decision(Order order, boolean proven) {
        /** A file with no numeric date to judge by reads day-first, which nothing contradicts. */
        public static final Decision UNPROVEN_DAY_FIRST = new Decision(Order.DAY_FIRST, false);

        /** True when this cell's date depends on which reading is used, and nothing in the file
         *  said which one is right. */
        public boolean leavesUnsettled(String cell) {
            return !proven && readingsDiffer(cell);
        }
    }

    private CsvDateOrder() {}

    /**
     * @param dateCells the cell each data row's date is read from, in file order; nulls and cells
     *                  that are not numeric dates are skipped
     * @throws ApiException {@link ErrorCode#IMPORT_DATE_ORDER_CONFLICT} when one cell proves
     *                      day-first and another proves month-first
     */
    public static Decision infer(Iterable<String> dateCells) {
        return infer(dateCells, java.util.List.of());
    }

    /**
     * Also lets the rest of the file settle an order its date column leaves open.
     *
     * <p>A short statement can have no transaction dated after the 12th, and still say elsewhere
     * which order it writes dates in: a real Kotak savings export prints "Period ... 01/01/2026 to
     * 31/01/2026" above its table. That is the same document stating a date, so it is proof too.
     * Only ever consulted when the date cells prove nothing, and only when it agrees with itself:
     * text above a table can come from a different system than the rows (a portal's own "printed
     * on" stamp), so a contradiction there is not a reason to refuse the file -- the order just
     * stays unproven, and the file is held as any unproven file is.
     *
     * @param otherDates numeric dates found elsewhere in the file -- the other date columns' cells
     *                   and the lines above the header ({@link #datesIn})
     */
    public static Decision infer(Iterable<String> dateCells, Iterable<String> otherDates) {
        Proof fromCells = proofIn(dateCells);
        if (fromCells.dayFirst && fromCells.monthFirst) throw new ApiException(ErrorCode.IMPORT_DATE_ORDER_CONFLICT);
        if (fromCells.monthFirst) return new Decision(Order.MONTH_FIRST, true);
        if (fromCells.dayFirst) return new Decision(Order.DAY_FIRST, true);
        Proof fromElsewhere = proofIn(otherDates);
        if (fromElsewhere.monthFirst && !fromElsewhere.dayFirst) return new Decision(Order.MONTH_FIRST, true);
        if (fromElsewhere.dayFirst && !fromElsewhere.monthFirst) return new Decision(Order.DAY_FIRST, true);
        return Decision.UNPROVEN_DAY_FIRST;
    }

    /** A numeric date with a four-digit year anywhere inside a piece of text, the separator the
     *  same both times. Four digits only: free text above a table also holds version numbers and
     *  references, and "1.2.26" is more likely one of those than a date. */
    private static final java.util.regex.Pattern DATE_IN_TEXT = java.util.regex.Pattern.compile(
            "(?<![\\d/.\\-])(\\d{1,2})([/\\-.])(\\d{1,2})\\2(\\d{4})(?![\\d/.\\-])");

    /** Every numeric date with a four-digit year inside {@code text}, for {@link #infer(Iterable, Iterable)}. */
    public static java.util.List<String> datesIn(String text) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (text == null) return found;
        Matcher m = DATE_IN_TEXT.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    /** A column whose heading says it holds a date or a period ("Value Date", "Statement
     *  Period", "Due Date"), whose dates {@link #infer(Iterable, Iterable)} may read as proof. */
    public static boolean namesADate(String header) {
        if (header == null) return false;
        String name = CsvParser.normalizeHeaderCell(header).toLowerCase(java.util.Locale.ROOT);
        return name.contains("date") || name.contains("period");
    }

    private record Proof(boolean dayFirst, boolean monthFirst) {}

    private static Proof proofIn(Iterable<String> cells) {
        boolean dayFirst = false, monthFirst = false;
        for (String cell : cells) {
            int[] fields = numericFields(cell);
            if (fields == null) continue;
            int first = fields[0], second = fields[1];
            // Only a number that can be a day and cannot be a month is proof. 13/13 is neither
            // reading; it fails to parse later and is reported as an unreadable date.
            if (first > 12 && first <= 31 && second >= 1 && second <= 12) dayFirst = true;
            if (second > 12 && second <= 31 && first >= 1 && first <= 12) monthFirst = true;
        }
        return new Proof(dayFirst, monthFirst);
    }

    /** True when both numbers could be the month and they differ, so the two readings name two
     *  different days. {@code 05/05/2026} reads the same either way and is not ambiguous. */
    static boolean readingsDiffer(String cell) {
        int[] fields = numericFields(cell);
        if (fields == null) return false;
        int first = fields[0], second = fields[1];
        return first >= 1 && first <= 12 && second >= 1 && second <= 12 && first != second;
    }

    /** The first two numbers of a numeric date, or null when the cell is not one. */
    static int[] numericFields(String cell) {
        if (cell == null) return null;
        // The same shape, after the same time-stripping, that CsvParser.parseDate(String, Order)
        // reads as a numeric date -- so a cell judged here is parsed the same way there.
        Matcher m = CsvParser.DAY_MONTH_YEAR.matcher(CsvParser.withoutCsvTime(cell));
        if (!m.matches()) return null;
        return new int[]{CsvParser.digitsToInt(m.group(1)), CsvParser.digitsToInt(m.group(3))};
    }
}
