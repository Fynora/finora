package com.finora.imports.pdf;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.util.TextSimilarity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR_BALANCE_CELL_CORROBORATED: a running-balance cell OCR misread by one glyph, restored to the
 * value the rows on both sides of it prove.
 *
 * <h2>The defect this exists for</h2>
 *
 * Measured on a phone-scanned Union Bank of India statement: four of its 289 balance cells came back
 * one glyph wrong -- a decimal point lost (a two-digit-rupee balance read a hundred times larger), a 5
 * read as 9, a 3 doubled, a 9 read as 5. Every amount was right; the chain broke at each misread cell
 * and re-joined at the next row, and the misread values reached the staged rows, the balance-chain
 * report, and -- because a broken chain leaves the day order ambiguous -- cost the statement its
 * derived opening and closing balance.
 *
 * <h2>Why this is evidence, not a guess</h2>
 *
 * A cell is replaced only when three independent things agree: the row above plus this row's own
 * amount says what the balance must be; the next readable balance, minus everything between, says the
 * SAME thing; and the printed value is one digit edit from it -- the shape of a single misrecognised
 * glyph. A same-day reordering, a missing row, or a misread AMOUNT breaks the second condition (the
 * chain does not re-join), so none of them is ever "repaired". Rows whose balance OCR could not read
 * at all are carried through by their amounts and left unread: a missing reading is not filled in.
 *
 * <p>Only ever applied to OCR text -- native extraction reads the document's own glyphs, and a
 * disagreement there is a fact about the document, not about recognition.
 */
final class OcrBalanceCellRepair {

    private OcrBalanceCellRepair() {}

    /**
     * Per row, the corroborated balance where the printed one is a single-glyph misread, else
     * {@code null}. {@code rows} must be in the document's own order.
     */
    static List<BigDecimal> corrections(List<StagedRow> rows) {
        List<BigDecimal> balances = new ArrayList<>(rows.size());
        List<BigDecimal> corrections = new ArrayList<>(rows.size());
        List<Integer> readable = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            balances.add(rows.get(i).balanceAfter());
            corrections.add(null);
            if (rows.get(i).balanceAfter() != null) readable.add(i);
        }
        for (int k = 1; k + 1 < readable.size(); k++) {
            int previous = readable.get(k - 1), current = readable.get(k), next = readable.get(k + 1);
            BigDecimal expected = balances.get(previous).add(movement(rows, previous + 1, current));
            BigDecimal printed = balances.get(current);
            if (printed.compareTo(expected) == 0) continue;
            BigDecimal nextExpected = expected.add(movement(rows, current + 1, next));
            if (balances.get(next).compareTo(nextExpected) != 0) continue;
            if (!oneGlyphApart(printed, expected)) continue;
            balances.set(current, expected);
            corrections.set(current, expected);
        }
        return corrections;
    }

    /** The signed movement of rows {@code from..to} inclusive. */
    private static BigDecimal movement(List<StagedRow> rows, int from, int to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = from; i <= to; i++) {
            StagedRow row = rows.get(i);
            if (row.amount() == null) continue;
            sum = "INCOME".equals(row.type()) ? sum.add(row.amount()) : sum.subtract(row.amount());
        }
        return sum;
    }

    /** At most one digit edit between the two values' printed digits, decimal point aside -- so a
     *  lost decimal point (digits identical, scale different) counts, and so does one wrong, extra
     *  or missing digit. Sign must match. */
    static boolean oneGlyphApart(BigDecimal printed, BigDecimal expected) {
        if (printed.signum() != expected.signum() && printed.signum() != 0 && expected.signum() != 0) return false;
        String a = printed.unscaledValue().abs().toString();
        String b = expected.unscaledValue().abs().toString();
        return TextSimilarity.editDistance(a, b) <= 1;
    }
}
