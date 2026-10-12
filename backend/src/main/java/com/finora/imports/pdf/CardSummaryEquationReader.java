package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a card summary printed as its own billing equation on one line, over one row of figures:
 *
 * <pre>
 *   Previous Balance - Payments - Credits + Purchase + Cash Advance + Other Debit&amp;Charges =Total Payment Due
 *      4,000.00 Dr     3,000.00    250.00    6,000.00       0.00              50.00             6,800.00 Dr
 * </pre>
 *
 * <p>Real shape: an Axis credit card. The whole equation is a single text run, so neither
 * {@link CreditCardSummaryExtractor}'s grid strategy (one label per run, each over its own figure)
 * nor its same-row strategy (a label with its figure beside it) can name any of the figures: the
 * statement read as a total due and nothing else, and no amount check ran on it.
 *
 * <p>The equation says what each figure is, so a figure is taken by its place in the row: the
 * n-th figure is the n-th term. Nothing is read unless every one of these holds, and each refusal
 * leaves the statement exactly as it was before this reader existed (no summary, no check):
 * <ul>
 *   <li>the line is the only one of its kind in the document, ends in {@code =} and the total
 *       due, and every term before that is a known one, on the side of the sign it belongs on;</li>
 *   <li>the first figures printed under it are exactly as many as the equation has terms, and each
 *       is an amount with paise. Text on the same row that is not an amount (a column of prose
 *       beside the panel shares the row on the real statement) is no figure and is passed over; a
 *       stray amount there makes the count wrong, which refuses;</li>
 *   <li>no figure is marked {@code Cr}. A balance in the customer's favour has not been seen on
 *       this layout, and every field of the evidence is a magnitude, so such a statement is left
 *       unread rather than read with the wrong sign.</li>
 * </ul>
 *
 * <p>A term's sign in the equation decides which side it falls on: an added term must be the
 * previous balance or one of the charge fields the extractor already knows, a subtracted one a
 * payment or credit. Subtracted terms are summed into {@code paymentsAndCredits}, the one field the
 * evidence has for them. An equation that names no previous balance, no purchases or no payment
 * reads as an incomplete summary, which {@link CreditCardSummaryExtractor#extract} discards whole.
 */
final class CardSummaryEquationReader {

    private CardSummaryEquationReader() {}

    /** How far under the equation its figures may sit. 13pt on the real statement. */
    private static final float MAX_FIGURE_ROW_GAP = 20.0f;

    /** Figures of one row share a baseline; the real ones differ by nothing at all. */
    private static final float SAME_ROW_Y_TOLERANCE = 2.0f;

    /** An amount with paise, as every figure of the real row is printed, with the Dr/Cr marker a
     *  balance carries. A bare integer (a phone number in the prose beside the panel) is not one. */
    private static final Pattern AMOUNT = Pattern.compile("^\\d[\\d,]*\\.\\d{2}(\\s*(?i:dr|cr))?$");
    private static final Pattern CREDIT_MARKED = Pattern.compile("(?i).*cr$");

    /** A term boundary: a sign with space on both sides. "Debit&Charges" and a hyphenated word
     *  carry no such sign and stay whole. */
    private static final Pattern SIGN = Pattern.compile("\\s+([+-])\\s+");

    /** What the equation subtracts, besides the payments labels the extractor already lists. */
    private static final Set<String> SUBTRACTED_TERMS = Set.of("payments", "credits");

    /** The equation's run, and one field key per term in printed order, the total due last. */
    private record Equation(PositionedText label, List<String> keys) {}

    static CreditCardSummaryEvidence read(List<PositionedText> runs) {
        List<Equation> equations = new ArrayList<>();
        for (PositionedText run : runs) {
            Equation equation = parse(run);
            if (equation != null) equations.add(equation);
        }
        if (equations.size() != 1) return CreditCardSummaryEvidence.NONE;
        Equation equation = equations.get(0);

        List<PositionedText> figures = figuresUnder(runs, equation.label());
        if (figures.size() != equation.keys().size()) return CreditCardSummaryEvidence.NONE;
        if (figures.stream().anyMatch(f -> CREDIT_MARKED.matcher(f.text().trim()).matches())) {
            return CreditCardSummaryEvidence.NONE;
        }

        BigDecimal previousBalance = null, purchases = null, cashAdvances = null, fees = null,
                paymentsAndCredits = null, totalAmountDue = null;
        for (int i = 0; i < figures.size(); i++) {
            BigDecimal parsed = CsvParser.parseNumeric(figures.get(i).text().trim());
            if (parsed == null) return CreditCardSummaryEvidence.NONE;
            BigDecimal amount = parsed.abs();
            switch (equation.keys().get(i)) {
                case "previousBalance" -> previousBalance = amount;
                case "purchases" -> purchases = amount;
                case "cashAdvances" -> cashAdvances = amount;
                case "fees" -> fees = amount;
                case "totalAmountDue" -> totalAmountDue = amount;
                default -> paymentsAndCredits = paymentsAndCredits == null ? amount : paymentsAndCredits.add(amount);
            }
        }
        return new CreditCardSummaryEvidence(previousBalance, purchases, cashAdvances, fees,
                paymentsAndCredits, totalAmountDue, ExtractionMethod.EQUATION_ROW, List.of());
    }

    /**
     * The run as an equation, one field key per term in printed order, or null when it is not one
     * this reader can stand behind. Subtracted terms all carry the key {@code paymentsAndCredits}.
     */
    private static Equation parse(PositionedText run) {
        String text = run.text();
        int equals = text.indexOf('=');
        // Everything after the first "=" must be the total due and nothing else.
        if (equals < 0 || !"totalAmountDue".equals(keyOf(text.substring(equals + 1)))) return null;

        String left = text.substring(0, equals);
        List<String> keys = new ArrayList<>();
        List<String> subtracted = new ArrayList<>();
        Matcher sign = SIGN.matcher(left);
        int from = 0;
        char pending = '+';
        boolean more = true;
        while (more) {
            more = sign.find();
            String term = StatementSummaryExtractor.normalize(left.substring(from, more ? sign.start() : left.length()));
            String key = keyOf(term);
            if (pending == '+') {
                // What is owed and what adds to it, each named once.
                if (key == null || !ADDED_KEYS.contains(key) || keys.contains(key)) return null;
            } else {
                if (!SUBTRACTED_TERMS.contains(term) && !"paymentsAndCredits".equals(key)) return null;
                if (subtracted.contains(term)) return null;
                subtracted.add(term);
                key = "paymentsAndCredits";
            }
            keys.add(key);
            if (more) {
                pending = sign.group(1).charAt(0);
                from = sign.end();
            }
        }
        keys.add("totalAmountDue");
        return new Equation(run, keys);
    }

    /** The fields an equation may add: the balance brought forward, and the three kinds of charge. */
    private static final Set<String> ADDED_KEYS = Set.of("previousBalance", "purchases", "cashAdvances", "fees");

    private static String keyOf(String term) {
        return CreditCardSummaryExtractor.keyFor(StatementSummaryExtractor.normalize(term));
    }

    /** The first amounts printed under the equation on its own page, left to right: every amount on
     *  the nearest row that has one. Empty when nothing is close enough. */
    private static List<PositionedText> figuresUnder(List<PositionedText> runs, PositionedText label) {
        List<PositionedText> below = runs.stream()
                .filter(t -> t.pageIndex() == label.pageIndex())
                .filter(t -> t.y() > label.y() && t.y() - label.y() <= MAX_FIGURE_ROW_GAP)
                .filter(t -> AMOUNT.matcher(t.text().trim()).matches())
                .toList();
        if (below.isEmpty()) return List.of();
        float rowY = (float) below.stream().mapToDouble(PositionedText::y).min().getAsDouble();
        return below.stream()
                .filter(t -> t.y() - rowY <= SAME_ROW_Y_TOLERANCE)
                .sorted(Comparator.comparingDouble(PositionedText::x))
                .toList();
    }
}
