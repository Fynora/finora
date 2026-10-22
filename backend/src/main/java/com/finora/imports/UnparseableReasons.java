package com.finora.imports;

import java.util.Set;

/**
 * Every reason the import engine gives for an unparseable row, written once, and the one place that
 * turns such a reason into something safe to persist.
 *
 * <p>A reason has two readers. The person who uploaded the statement sees it on their own review
 * screen, beside the row, and there it should quote the cell it could not read -- that is the most
 * useful thing it can say. {@link UnparseableRowSummary} counts it into
 * {@code unparseable_summary_json}, an engineering table read by admins and kept indefinitely, and
 * there a quoted cell is a line of somebody's bank statement. {@link #summaryKey} is the bridge: the
 * same reason with any quoted cell replaced by its {@link ValueShape}.
 *
 * <p>It works from an allowlist rather than by scrubbing quotes out of whatever text arrives. A
 * scrubber only catches values that happen to be quoted; a reason added later as
 * {@code "Bad value " + cell} would pass straight through it. Here a reason that is not one of the
 * texts below is counted under {@link #UNCLASSIFIED} and its text is dropped, so a new reason can
 * cost a summary some detail but can never put a value into it. That is also why the generators use
 * these constants instead of their own literals: a reason written inline would land in
 * {@link #UNCLASSIFIED} rather than under its own name.
 */
public final class UnparseableReasons {

    private UnparseableReasons() {}

    /** CSV: no line of the file was recognised as a column header. */
    public static final String NO_HEADER_ROW = "No column header row was recognized anywhere in this file";

    /** PDF: no transaction table anywhere, so every extracted line is surfaced as-is. */
    public static final String NO_TRANSACTION_TABLE = "No transaction table was recognized anywhere in this document";

    /** CSV and PDF: a value under a column the normaliser does not recognise, and no transactional amount. */
    public static final String UNRECOGNIZED_COLUMN_VALUE =
            "Row has a value in an unrecognized column and no recognized transactional "
                    + "amount column, so it could not be confidently classified as a transaction "
                    + "or excluded as a balance marker";

    /** CSV and PDF: a recognised transactional amount column holds a value no numeric format accepts. */
    public static final String UNPARSEABLE_RECOGNIZED_AMOUNT =
            "Row has a value in a recognized transactional amount column that could not be "
                    + "parsed as a number, so it could not be confidently classified as a "
                    + "transaction or excluded as a balance marker";

    /** PDF: the running balance moved on a row that carried no readable amount. */
    public static final String BALANCE_MOVED_WITHOUT_AN_AMOUNT =
            "The running balance changed on this row, but no amount was read for it, so it "
                    + "could not be staged as a transaction";

    /** {@link TransactionNormalizer#explainFailure}: no date column, or every date column blank. */
    public static final String NO_DATE_COLUMN = "No column recognized as a date";

    /** {@link TransactionNormalizer#explainFailure}: no amount or balance column, or all blank. */
    public static final String NO_AMOUNT_COLUMN = "No column recognized as an amount or balance";

    /** {@link TransactionNormalizer#explainFailure}: date and amount read, row still refused. */
    public static final String REJECTED_AFTER_PARSE = "Date and amount both parsed but the row was still rejected";

    private static final String DATE_UNREADABLE_PREFIX = "Date value \"";
    private static final String DATE_UNREADABLE_SUFFIX = "\" didn't match any known date format";
    private static final String AMOUNT_UNREADABLE_PREFIX = "Amount value \"";
    private static final String AMOUNT_UNREADABLE_SUFFIX = "\" didn't match any known numeric format";

    /** A row with no reason at all. Kept from before this class existed, so old and new rows agree. */
    public static final String UNSPECIFIED = "unspecified";

    /** Any reason that is not one of this class's texts. Its own text is never kept. */
    public static final String UNCLASSIFIED = "unclassified (reason text not recorded)";

    private static final Set<String> FIXED = Set.of(
            NO_HEADER_ROW, NO_TRANSACTION_TABLE, UNRECOGNIZED_COLUMN_VALUE, UNPARSEABLE_RECOGNIZED_AMOUNT,
            BALANCE_MOVED_WITHOUT_AN_AMOUNT, NO_DATE_COLUMN, NO_AMOUNT_COLUMN, REJECTED_AFTER_PARSE);

    /** The review-screen reason for a date cell no format accepts. Quotes the cell as given. */
    public static String dateUnreadable(String cell) {
        return DATE_UNREADABLE_PREFIX + cell + DATE_UNREADABLE_SUFFIX;
    }

    /** The review-screen reason for an amount cell no numeric format accepts. Quotes the cell as given. */
    public static String amountUnreadable(String cell) {
        return AMOUNT_UNREADABLE_PREFIX + cell + AMOUNT_UNREADABLE_SUFFIX;
    }

    /**
     * {@code reason} as it may be persisted: a fixed reason unchanged, a quoting reason with the cell
     * replaced by its {@link ValueShape} (trimmed first, as {@link TransactionNormalizer#failureReasonKey}
     * does, so both histograms bucket one format the same way), anything else {@link #UNCLASSIFIED}.
     *
     * <p>The cell is everything between the template's fixed prefix and suffix, so a cell that itself
     * contains quotes is still masked whole.
     */
    public static String summaryKey(String reason) {
        if (reason == null) return UNSPECIFIED;
        if (FIXED.contains(reason)) return reason;
        String cell = between(reason, DATE_UNREADABLE_PREFIX, DATE_UNREADABLE_SUFFIX);
        if (cell != null) return dateUnreadable(ValueShape.of(cell.trim()));
        cell = between(reason, AMOUNT_UNREADABLE_PREFIX, AMOUNT_UNREADABLE_SUFFIX);
        if (cell != null) return amountUnreadable(ValueShape.of(cell.trim()));
        return UNCLASSIFIED;
    }

    private static String between(String text, String prefix, String suffix) {
        if (text.length() < prefix.length() + suffix.length()) return null;
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return null;
        return text.substring(prefix.length(), text.length() - suffix.length());
    }
}
