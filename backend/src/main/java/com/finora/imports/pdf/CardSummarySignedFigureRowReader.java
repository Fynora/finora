package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a card summary whose figures are joined by the signs of its own billing equation, each
 * figure under a label wrapped over two lines in its own column:
 *
 * <pre>
 *                               Purchases &amp;
 *   Previous                                       Total
 *                  Payments     Other Charges
 *   Amount Due                                     Amount Due
 *
 *   Rs. 9,000.00  -  Rs. 9,000.00  +  Rs. 4,250.00  =  Rs. 4,250.00
 * </pre>
 *
 * <p>Real shape: a Kotak credit card's "My Summary" box. Every word of it is a text run of its
 * own, and no label is whole on one line, so neither {@link CreditCardSummaryExtractor}'s grid
 * strategy (which matches a label one run at a time) nor its same-row strategy (which joins runs
 * only along a line, and looks for the figure beside the label) could name a figure. The statement
 * read as a total due and nothing else, and no amount check ran on it.
 *
 * <p>The row is found by its signs, not by its labels: a line made of nothing but amounts, an
 * optional currency mark before each, and {@code -}, {@code +}, {@code =} between them. The signs
 * then bound each figure's column, the words printed above the row inside a column are joined top
 * to bottom into that figure's label, and the label still has to be one this reader knows.
 * Nothing is read unless every one of these holds, and each refusal leaves the statement exactly
 * as it was before this reader existed (no summary, no check):
 * <ul>
 *   <li>the document has exactly one such row, with one {@code =} and a single figure after it;</li>
 *   <li>the row holds nothing else at all: any other word or number on its line refuses;</li>
 *   <li>every figure has a label above it, no label stands over two figures, and each label is
 *       a known one on the side of the sign it belongs on: what is added is the previous balance
 *       or a charge, each named once; what is subtracted a payment; what follows {@code =} the
 *       total due.</li>
 * </ul>
 */
final class CardSummarySignedFigureRowReader {

    private CardSummarySignedFigureRowReader() {}

    /** The signs sit half a point under the figures on the real row. */
    private static final float ROW_Y_TOLERANCE = 2.0f;

    /** How far above the row a label line may sit. The top line of the tallest real label is
     *  36.5pt above it; the box's own heading, 60pt above, is not a label. */
    private static final float MAX_LABEL_HEIGHT = 45.0f;

    /** An amount with paise, as each figure of the real row is printed. */
    private static final Pattern AMOUNT = Pattern.compile("^\\d[\\d,]*\\.\\d{2}$");
    private static final Pattern CURRENCY_MARK = Pattern.compile("^(?i:rs\\.?|inr)$");
    private static final Set<String> SIGNS = Set.of("+", "-", "=");

    /** The fields an equation may add: the balance brought forward, and the three kinds of charge. */
    private static final Set<String> ADDED_KEYS = Set.of("previousBalance", "purchases", "cashAdvances", "fees");

    /** Labels this box uses that the extractor's own lists do not carry. Kept here rather than
     *  added there, so the two older strategies go on treating them as text that is not a label. */
    private static final Map<String, String> OWN_LABELS = Map.of(
            "previous amount due", "previousBalance",
            "payments", "paymentsAndCredits");

    /** One figure of the row, the sign printed before it ('+' for the first), and the span from
     *  its currency mark to its last digit. */
    private record Term(char sign, PositionedText figure, float left, float right) {
        float centre() { return (left + right) / 2f; }
    }

    private record SignedRow(List<Term> terms, List<Float> signCentres, PositionedText equals) {}

    static CreditCardSummaryEvidence read(List<PositionedText> runs) {
        List<SignedRow> rows = new ArrayList<>();
        for (PositionedText run : runs) {
            if (!"=".equals(run.text().trim())) continue;
            SignedRow row = rowAround(run, runs);
            if (row != null) rows.add(row);
        }
        if (rows.size() != 1) return CreditCardSummaryEvidence.NONE;
        SignedRow row = rows.get(0);

        BigDecimal previousBalance = null, purchases = null, cashAdvances = null, fees = null,
                paymentsAndCredits = null, totalAmountDue = null;
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < row.terms().size(); i++) {
            Term term = row.terms().get(i);
            String label = labelAbove(row, i, runs);
            if (label == null || seen.contains(label)) return CreditCardSummaryEvidence.NONE;
            seen.add(label);
            String key = keyOf(label);
            BigDecimal amount = CsvParser.parseNumeric(term.figure().text().trim());
            if (key == null || amount == null) return CreditCardSummaryEvidence.NONE;
            amount = amount.abs();

            boolean afterEquals = i == row.terms().size() - 1;
            if (afterEquals) {
                if (!"totalAmountDue".equals(key)) return CreditCardSummaryEvidence.NONE;
                totalAmountDue = amount;
            } else if (term.sign() == '-') {
                if (!"paymentsAndCredits".equals(key)) return CreditCardSummaryEvidence.NONE;
                paymentsAndCredits = paymentsAndCredits == null ? amount : paymentsAndCredits.add(amount);
            } else {
                if (!ADDED_KEYS.contains(key)) return CreditCardSummaryEvidence.NONE;
                switch (key) {
                    case "previousBalance" -> {
                        if (previousBalance != null) return CreditCardSummaryEvidence.NONE;
                        previousBalance = amount;
                    }
                    case "purchases" -> {
                        if (purchases != null) return CreditCardSummaryEvidence.NONE;
                        purchases = amount;
                    }
                    case "cashAdvances" -> {
                        if (cashAdvances != null) return CreditCardSummaryEvidence.NONE;
                        cashAdvances = amount;
                    }
                    default -> {
                        if (fees != null) return CreditCardSummaryEvidence.NONE;
                        fees = amount;
                    }
                }
            }
        }
        return new CreditCardSummaryEvidence(previousBalance, purchases, cashAdvances, fees,
                paymentsAndCredits, totalAmountDue, ExtractionMethod.SIGNED_FIGURE_ROW, List.of());
    }

    private static String keyOf(String label) {
        String own = OWN_LABELS.get(label);
        return own != null ? own : CreditCardSummaryExtractor.keyFor(label);
    }

    /**
     * Everything on the line of this {@code =}, read left to right as {@code figure (sign figure)*},
     * or null when the line is anything else: a word, a number that is not an amount with paise, a
     * sign with no figure on one side of it, or any sign at all after the {@code =}, which leaves
     * exactly one figure there.
     */
    private static SignedRow rowAround(PositionedText equals, List<PositionedText> runs) {
        List<PositionedText> line = runs.stream()
                .filter(t -> t.pageIndex() == equals.pageIndex()
                        && Math.abs(t.y() - equals.y()) <= ROW_Y_TOLERANCE)
                .filter(t -> !t.text().isBlank())
                .sorted(Comparator.comparingDouble(PositionedText::x))
                .toList();

        List<Term> terms = new ArrayList<>();
        List<Float> signCentres = new ArrayList<>();
        char pendingSign = '+';
        boolean expectingFigure = true;
        boolean equalsSeen = false;
        PositionedText mark = null;
        for (PositionedText t : line) {
            String text = t.text().trim();
            if (expectingFigure) {
                if (CURRENCY_MARK.matcher(text).matches()) {
                    mark = t;
                } else if (AMOUNT.matcher(text).matches()) {
                    terms.add(new Term(pendingSign, t, mark == null ? t.x() : mark.x(), t.endX()));
                    mark = null;
                    expectingFigure = false;
                } else {
                    return null;
                }
            } else {
                if (!SIGNS.contains(text) || equalsSeen) return null;
                equalsSeen = "=".equals(text);
                pendingSign = text.charAt(0);
                signCentres.add((t.x() + t.endX()) / 2f);
                expectingFigure = true;
            }
        }
        // A row that stops on a sign has no figure for what its figures come to.
        if (expectingFigure) return null;
        return new SignedRow(terms, signCentres, equals);
    }

    /**
     * The words printed above the row inside one figure's column, joined line by line, normalised;
     * or null when there are none. Whatever sits there is part of the label, so a stray word or
     * number makes it a label nobody knows, which refuses. A column runs from
     * the sign before its figure to the sign after it; the first and last columns, with a sign on
     * one side only, reach as far the other way.
     */
    private static String labelAbove(SignedRow row, int index, List<PositionedText> runs) {
        Term term = row.terms().get(index);
        float left = index == 0
                ? 2 * term.centre() - row.signCentres().get(0)
                : row.signCentres().get(index - 1);
        float right = index == row.terms().size() - 1
                ? 2 * term.centre() - row.signCentres().get(index - 1)
                : row.signCentres().get(index);
        float rowY = row.equals().y();

        List<PositionedText> words = runs.stream()
                .filter(t -> t.pageIndex() == row.equals().pageIndex())
                .filter(t -> rowY - t.y() > ROW_Y_TOLERANCE && rowY - t.y() <= MAX_LABEL_HEIGHT)
                .filter(t -> {
                    float centre = (t.x() + t.endX()) / 2f;
                    return centre > left && centre < right;
                })
                .filter(t -> !t.text().isBlank())
                .sorted(Comparator.comparingDouble(PositionedText::y))
                .toList();
        if (words.isEmpty()) return null;

        // Lines top to bottom, each left to right. Words of one line share a baseline.
        List<List<PositionedText>> lines = new ArrayList<>();
        for (PositionedText word : words) {
            List<PositionedText> last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && word.y() - last.get(0).y() <= ROW_Y_TOLERANCE) last.add(word);
            else lines.add(new ArrayList<>(List.of(word)));
        }
        StringBuilder label = new StringBuilder();
        for (List<PositionedText> line : lines) {
            line.sort(Comparator.comparingDouble(PositionedText::x));
            for (PositionedText word : line) label.append(label.isEmpty() ? "" : " ").append(word.text().trim());
        }
        return StatementSummaryExtractor.normalize(label.toString());
    }
}
