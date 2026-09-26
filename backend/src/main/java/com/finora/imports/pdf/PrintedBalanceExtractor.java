package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PRINTED_OPENING_CLOSING_BALANCE. The opening and closing balance a statement prints as its own
 * summary, read the two ways real statements print them: as a GRID (a label row -- "Opening
 * Balance  Total Credits  Total Debits  Closing Balance" -- with the values on a row below, a real
 * Bandhan Bank statement, whose values carry an "INR" prefix) and INLINE (the label and its amount
 * on one row, a real Canara Bank statement, whose two summary lines otherwise surfaced as
 * unparseable rows). Modelled on {@link CreditLimitGridExtractor}.
 *
 * <p>Deliberately not applied to a credit-card section by the caller: a card summary's "OPENING
 * BALANCE" (a real HSBC card) is the previous statement's balance, a different concept from a
 * ledger's opening balance, and cards have no running balance to anchor. Until now a PDF section's
 * opening and closing balances came only from the balance chain; the printed values are the
 * statement's own declaration and the fallback when the chain yields none (audit F-09).
 */
public final class PrintedBalanceExtractor {
    private PrintedBalanceExtractor() {}

    public record PrintedBalances(BigDecimal opening, BigDecimal closing) {
        public static final PrintedBalances NONE = new PrintedBalances(null, null);
        public boolean isEmpty() { return opening == null && closing == null; }
    }

    private static final Pattern OPENING_LABEL = Pattern.compile(
            "(?i)^(?:opening\\s+balance|balance\\s+brought\\s+forward|balance\\s+b/f|opening\\s+bal\\.?)$");
    private static final Pattern CLOSING_LABEL = Pattern.compile(
            "(?i)^(?:closing\\s+balance|balance\\s+carried\\s+forward|balance\\s+c/f|closing\\s+bal\\.?)$");
    /** "INR10,728.84", "Rs. 1,15,238.60", "₹ 500.00", "7,077.40 Cr" -- the amount with the currency
     *  marker a summary prints around it. */
    private static final Pattern CURRENCY_DECORATION = Pattern.compile("(?i)^(?:inr|rs\\.?|\\u20b9)\\s*|\\s*(?:cr|dr)\\.?$");
    /** A transaction table's own header row: it names a date column, which no summary grid does.
     *  Measured on two real HDFC statements whose ledger header ends in "Closing Balance": read as
     *  a summary label, the value under it was the FIRST transaction's balance. */
    private static final Pattern TABLE_HEADER_WORD = Pattern.compile("(?i)\\bdate\\b|narration|particulars|description");
    private static final float MAX_ROW_GAP = 40.0f;
    private static final float SAME_ROW_MAX_X_DISTANCE = 320.0f;

    public static PrintedBalances extract(List<PositionedText> runs, DocumentContext ctx) {
        if (runs == null || runs.isEmpty()) return PrintedBalances.NONE;
        List<List<PositionedText>> rows = StatementSummaryExtractor.groupIntoRows(runs);
        BigDecimal opening = null, closing = null;
        for (int i = 0; i < rows.size() && (opening == null || closing == null); i++) {
            if (isATransactionTableHeader(rows.get(i))) continue;
            for (PositionedText label : rows.get(i)) {
                String text = label.text().trim().replaceAll(":$", "").trim();
                boolean isOpening = opening == null && OPENING_LABEL.matcher(text).matches();
                boolean isClosing = closing == null && CLOSING_LABEL.matcher(text).matches();
                if (!isOpening && !isClosing) continue;
                // Inline first: a figure to the label's right on the same row is the value; only a
                // label with nothing beside it is a grid heading. Measured on a real (scanned) HSBC
                // statement: its "BALANCE BROUGHT FORWARD" row carries the figure beside the label,
                // and reading below first took the next row's 12-digit reference number instead.
                BigDecimal value = valueBeside(rows.get(i), label);
                if (value == null) value = valueBelow(rows, i, label);
                if (value == null) continue;
                if (isOpening) opening = value; else closing = value;
            }
        }
        if (opening == null && closing == null) return PrintedBalances.NONE;
        if (ctx != null) ctx.record("PRINTED_OPENING_CLOSING_BALANCE");
        return new PrintedBalances(opening, closing);
    }

    private static boolean isATransactionTableHeader(List<PositionedText> row) {
        for (PositionedText t : row) if (TABLE_HEADER_WORD.matcher(t.text()).find()) return true;
        return false;
    }

    private static BigDecimal valueBelow(List<List<PositionedText>> rows, int labelRowIndex, PositionedText label) {
        int page = label.pageIndex();
        for (int j = labelRowIndex + 1; j < rows.size(); j++) {
            List<PositionedText> candidateRow = rows.get(j);
            PositionedText first = candidateRow.get(0);
            if (first.pageIndex() != page) break;
            if (first.y() - label.y() > MAX_ROW_GAP) break;
            PositionedText value = StatementSummaryExtractor.valueUnder(label, candidateRow);
            if (value == null) continue;
            BigDecimal parsed = amountOf(value.text());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static BigDecimal valueBeside(List<PositionedText> row, PositionedText label) {
        PositionedText best = null;
        for (PositionedText candidate : row) {
            if (candidate == label || candidate.x() <= label.x()) continue;
            if (candidate.x() - label.endX() > SAME_ROW_MAX_X_DISTANCE) continue;
            if (amountOf(candidate.text()) == null) continue;
            if (best == null || candidate.x() < best.x()) best = candidate;
        }
        return best == null ? null : amountOf(best.text());
    }

    /** The amount, or null when the text is not one amount (a heading, prose, several figures, or
     *  a bare digit run -- a reference number -- with neither a decimal point nor a thousands
     *  separator; every printed balance in the real corpus carries at least one of the two). */
    static BigDecimal amountOf(String text) {
        String bare = CURRENCY_DECORATION.matcher(text.trim()).replaceAll("").trim();
        if (bare.isEmpty() || bare.contains(" ") || !bare.matches("[\\d,]+(?:\\.\\d{1,2})?")) return null;
        if (!bare.contains(".") && !bare.contains(",")) return null;
        return CsvParser.parseNumeric(bare);
    }
}
