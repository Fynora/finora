package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the payment due date and credit limit from a credit-card payment summary whose box labels
 * extract as nothing at all -- the one real shape evidenced on both HSBC credit-card statements in
 * the corpus. Their "Payment due date", "Minimum payment due", "Statement period", "Total payment
 * due", "Credit limit" and "Cash limit" labels are drawn in fonts the PDF references but does not
 * embed, so PDFBox maps every glyph to nothing; only the values are text. With no label anywhere,
 * {@link PdfMetadataExtractor}, {@link PaymentDueDateGridExtractor} and
 * {@link CreditLimitGridExtractor} can never reach them.
 *
 * <p>Safety comes from document structure, never from a value's shape alone (the same rule as
 * {@link StatementTitleDateRangeExtractor}). Everything is located relative to one fixed line of
 * boilerplate, the cheque-payment instruction beginning {@link #ANCHOR}, which a corpus sweep found
 * on these two HSBC statements and nowhere else; both statements place every run at the same x/y:
 *
 * <ul>
 *   <li>Above the instruction, the statement-period row: a run reading "&lt;date&gt; To", the end
 *       date beside it, and the total payment due. The period end is read from it.</li>
 *   <li>The row directly above that: the payment due date and the minimum payment due -- exactly
 *       one date and one amount, nothing else. The date must fall after the period end and within
 *       {@link #MAX_DAYS_AFTER_PERIOD_END} days of it.</li>
 *   <li>Below the instruction, a row holding exactly two masked card numbers; the row directly
 *       below that holds exactly two amounts, both to the left of the instruction line: the credit
 *       limit, then the cash limit. The credit limit must be positive and at least the cash
 *       limit.</li>
 * </ul>
 *
 * <p>Anything that does not match exactly reads nothing. Wired as the last tier for both fields
 * (see PdfPreviewGenerator): every labelled source is tried first.
 */
public final class UnlabelledCardPaymentSummaryExtractor {

    private UnlabelledCardPaymentSummaryExtractor() {}

    static final String ANCHOR = "Please make all cheques/demand drafts duly crossed, payable to \"HSBC";

    /** The period row sits 41.7pt above the instruction line on both real statements. */
    private static final float MAX_GAP_TO_PERIOD_ROW = 60.0f;
    /** The due-date row sits 31pt above the period row; the card numbers 50.4pt below the
     *  instruction; the limits 22pt below the card numbers. */
    private static final float MAX_GAP_BETWEEN_ROWS = 40.0f;
    /** Statement period end to payment due date: 18 days on both real statements. */
    static final int MAX_DAYS_AFTER_PERIOD_END = 60;

    private static final String DATE = "\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4}";
    private static final Pattern BARE_DATE = Pattern.compile("^(" + DATE + ")$");
    private static final Pattern PERIOD_START = Pattern.compile("^(" + DATE + ")\\s+To$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MASKED_CARD = Pattern.compile("^\\d{2}xx xxxx xxxx \\d{4}$", Pattern.CASE_INSENSITIVE);
    // STRICT, era defaulted to CE: the SMART default clamped "30 Feb 2026" to 2026-02-28 and
    // "31 Apr 2026" to 2026-04-30 (measured). Same fix, and the same reason for keeping "yyyy" over
    // "uuuu", as PdfMetadataExtractor.ci().
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMM yyyy")
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);

    /** {@code totalPaymentDue} is the box's "Total payment due": what the card bills this cycle.
     *  The same statement's summary table prints a "Net Outstanding Balance" that also counts a
     *  loan's future instalments, so on a card carrying a loan the two differ by exactly the loan
     *  still outstanding. */
    public record CardPaymentSummary(LocalDate paymentDueDate, BigDecimal creditLimit, BigDecimal totalPaymentDue) {
        public static final CardPaymentSummary NONE = new CardPaymentSummary(null, null, null);
    }

    public static CardPaymentSummary extract(List<PositionedText> runs) {
        return extract(runs, null);
    }

    /** {@code ctx} is accepted for symmetry with the other extractors; the caller records the
     *  capability, because only the caller knows whether a value read here was actually used. */
    public static CardPaymentSummary extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return CardPaymentSummary.NONE;
        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        for (int i = 0; i < rows.size(); i++) {
            PositionedText anchor = anchorIn(rows.get(i));
            if (anchor == null) continue;
            LocalDate dueDate = dueDateAbove(rows, i, anchor);
            BigDecimal creditLimit = creditLimitBelow(rows, i, anchor);
            BigDecimal totalPaymentDue = totalPaymentDueAbove(rows, i, anchor);
            if (dueDate == null && creditLimit == null && totalPaymentDue == null) continue;
            return new CardPaymentSummary(dueDate, creditLimit, totalPaymentDue);
        }
        return CardPaymentSummary.NONE;
    }

    private static PositionedText anchorIn(List<PositionedText> row) {
        for (PositionedText t : row) {
            if (t.text().trim().startsWith(ANCHOR)) return t;
        }
        return null;
    }

    private static LocalDate dueDateAbove(List<List<PositionedText>> rows, int anchorRow, PositionedText anchor) {
        // The nearest row above the instruction carrying the period: "<date> To", the end date.
        for (int j = anchorRow - 1; j >= 0; j--) {
            List<PositionedText> row = rows.get(j);
            if (!onPageAbove(row, anchor, MAX_GAP_TO_PERIOD_ROW)) return null;
            LocalDate periodEnd = periodEndIn(row, anchor);
            if (periodEnd == null) continue;
            // The nearest row above with anything on the instruction line's side: on the real page
            // the cardholder's name, on the left, sits between the due-date row and this one.
            for (int k = j - 1; k >= 0; k--) {
                List<PositionedText> above = rows.get(k);
                if (!onPageAbove(above, row.get(0), MAX_GAP_BETWEEN_ROWS)) return null;
                List<PositionedText> side = onAnchorsSide(above, anchor);
                if (!side.isEmpty()) return dueDateIn(side, anchor, periodEnd);
            }
            return null;
        }
        return null;
    }

    /** The period row's own third figure: exactly "&lt;date&gt; To", the end date, and one amount on
     *  the instruction line's side, nothing else. */
    private static BigDecimal totalPaymentDueAbove(List<List<PositionedText>> rows, int anchorRow, PositionedText anchor) {
        for (int j = anchorRow - 1; j >= 0; j--) {
            List<PositionedText> row = rows.get(j);
            if (!onPageAbove(row, anchor, MAX_GAP_TO_PERIOD_ROW)) return null;
            if (periodEndIn(row, anchor) == null) continue;
            List<PositionedText> side = onAnchorsSide(row, anchor);
            if (side.size() != 3 || !PERIOD_START.matcher(side.get(0).text().trim()).matches()) return null;
            BigDecimal total = CsvParser.parseNumeric(side.get(2).text().trim());
            return total == null || total.signum() < 0 ? null : total;
        }
        return null;
    }

    /** Only the instruction line's side of the page counts: on the real statement the address
     *  block on the left shares the period row's height. */
    private static LocalDate periodEndIn(List<PositionedText> row, PositionedText anchor) {
        List<PositionedText> side = onAnchorsSide(row, anchor);
        for (int k = 0; k + 1 < side.size(); k++) {
            Matcher start = PERIOD_START.matcher(side.get(k).text().trim());
            if (!start.matches()) continue;
            Matcher end = BARE_DATE.matcher(side.get(k + 1).text().trim());
            if (!end.matches()) return null;
            return parseDate(end.group(1));
        }
        return null;
    }

    /** Exactly one date and one amount on the instruction line's side, the date over the
     *  instruction line, after the period end. */
    private static LocalDate dueDateIn(List<PositionedText> row, PositionedText anchor, LocalDate periodEnd) {
        if (row.size() != 2) return null;
        List<PositionedText> byX = byX(row);
        PositionedText date = byX.get(0);
        PositionedText amount = byX.get(1);
        if (!BARE_DATE.matcher(date.text().trim()).matches()) return null;
        if (CsvParser.parseNumeric(amount.text().trim()) == null) return null;
        if (date.x() < anchor.x() || date.x() > anchor.endX()) return null;
        LocalDate due = parseDate(date.text().trim());
        if (due == null || !due.isAfter(periodEnd)) return null;
        if (ChronoUnit.DAYS.between(periodEnd, due) > MAX_DAYS_AFTER_PERIOD_END) return null;
        return due;
    }

    private static BigDecimal creditLimitBelow(List<List<PositionedText>> rows, int anchorRow, PositionedText anchor) {
        // The nearest row below the instruction holding exactly the two masked card numbers.
        for (int j = anchorRow + 1; j < rows.size(); j++) {
            List<PositionedText> row = rows.get(j);
            if (!onPageBelow(row, anchor, 2 * MAX_GAP_BETWEEN_ROWS)) return null;
            if (!isCardNumberRow(row, anchor)) continue;
            if (j + 1 >= rows.size()) return null;
            List<PositionedText> limits = rows.get(j + 1);
            if (!onPageBelow(limits, row.get(0), MAX_GAP_BETWEEN_ROWS)) return null;
            return creditLimitIn(limits, anchor);
        }
        return null;
    }

    /** Only the instruction line's side counts: the state line on the left shares this row. */
    private static boolean isCardNumberRow(List<PositionedText> row, PositionedText anchor) {
        List<PositionedText> side = onAnchorsSide(row, anchor);
        return side.size() == 2 && side.stream().allMatch(t -> MASKED_CARD.matcher(t.text().trim()).matches());
    }

    private static List<PositionedText> onAnchorsSide(List<PositionedText> row, PositionedText anchor) {
        return byX(row.stream().filter(t -> t.x() >= anchor.x() - 5f).toList());
    }

    private static List<PositionedText> byX(List<PositionedText> runs) {
        return runs.stream().sorted(java.util.Comparator.comparingDouble(PositionedText::x)).toList();
    }

    /** Exactly two amounts, both left of the instruction line: credit limit, then cash limit. */
    private static BigDecimal creditLimitIn(List<PositionedText> row, PositionedText anchor) {
        if (row.size() != 2) return null;
        PositionedText left = row.get(0).x() <= row.get(1).x() ? row.get(0) : row.get(1);
        PositionedText right = left == row.get(0) ? row.get(1) : row.get(0);
        if (left.endX() >= anchor.x() || right.endX() >= anchor.x()) return null;
        BigDecimal credit = CsvParser.parseNumeric(left.text().trim());
        BigDecimal cash = CsvParser.parseNumeric(right.text().trim());
        if (credit == null || cash == null || credit.signum() <= 0 || credit.compareTo(cash) < 0) return null;
        return credit;
    }

    private static boolean onPageAbove(List<PositionedText> row, PositionedText below, float maxGap) {
        PositionedText first = row.get(0);
        return first.pageIndex() == below.pageIndex() && below.y() - first.y() > 0 && below.y() - first.y() <= maxGap;
    }

    private static boolean onPageBelow(List<PositionedText> row, PositionedText above, float maxGap) {
        PositionedText first = row.get(0);
        return first.pageIndex() == above.pageIndex() && first.y() - above.y() > 0 && first.y() - above.y() <= maxGap;
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim().replaceAll("\\s+", " "), DATE_FORMAT);
        } catch (Exception ignored) {
            return null;
        }
    }
}
