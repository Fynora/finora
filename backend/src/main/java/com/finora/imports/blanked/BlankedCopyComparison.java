package com.finora.imports.blanked;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.trace.PdfTraceRedactor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Whether a blanked copy reads the same as the real statement it came from.
 *
 * <p>Compares only what reading produces. Not compared: the account holder's name and number
 * (masked in the copy by design), and the category, merchant and duplicate fields (they come from
 * description words and the customer's own data). A difference names a field and a row, never a
 * value: a real value can be text the copy hid, such as a phone number the real reading took for
 * an amount. The only text a difference carries is the copy's own masked tokens, which the copy
 * already contains.
 *
 * <p>A description is compared with what the redactor would have written for the real one, token
 * by token where a line break changed how the two were masked (see {@link #sameDescription}), so
 * the two match only when the same words were grouped into that row in the same order.
 */
public final class BlankedCopyComparison {

    public enum Field { SECTION_COUNT, ROW_COUNT, UNPARSEABLE_COUNT, DATE, AMOUNT, DIRECTION, BALANCE,
        DESCRIPTION, DETECTED_ACCOUNT, PERIOD_START, PERIOD_END, OPENING_BALANCE, CLOSING_BALANCE, FINDINGS,
        /** The row was read from different printed lines (see {@link RowMapBlock}). */
        ROW_LINES }

    public record Difference(int section, Integer row, Field field, List<String> maskedTokens) {}

    public record Result(List<Difference> differences) {
        public boolean matches() { return differences.isEmpty(); }
    }

    private BlankedCopyComparison() {}

    public static Result compare(List<StagedAccountSection> real, List<StagedAccountSection> copy) {
        List<Difference> out = new ArrayList<>();
        if (real.size() != copy.size()) out.add(new Difference(0, null, Field.SECTION_COUNT, List.of()));
        for (int s = 0; s < Math.min(real.size(), copy.size()); s++) {
            compareSection(s, real.get(s), copy.get(s), out);
        }
        return new Result(List.copyOf(out));
    }

    /**
     * The same comparison, plus the row maps of the two readings (0-based section, then shown row;
     * see {@code RowMapSink}): a row present in either map whose lines differ, or that only one map
     * has, is a {@link Field#ROW_LINES} difference. It carries no tokens: the lines are positions,
     * and the copy already holds them.
     */
    public static Result compare(List<StagedAccountSection> real, List<StagedAccountSection> copy,
                                 Map<Integer, Map<Integer, RowLines>> realRowMap,
                                 Map<Integer, Map<Integer, RowLines>> copyRowMap) {
        List<Difference> out = new ArrayList<>(compare(real, copy).differences());
        TreeSet<Integer> sections = new TreeSet<>(realRowMap.keySet());
        sections.addAll(copyRowMap.keySet());
        for (int s : sections) {
            Map<Integer, RowLines> a = realRowMap.getOrDefault(s, Map.of());
            Map<Integer, RowLines> b = copyRowMap.getOrDefault(s, Map.of());
            TreeSet<Integer> rows = new TreeSet<>(a.keySet());
            rows.addAll(b.keySet());
            for (int r : rows) {
                if (!Objects.equals(a.get(r), b.get(r))) out.add(new Difference(s, r, Field.ROW_LINES, List.of()));
            }
        }
        return new Result(List.copyOf(out));
    }

    private static void compareSection(int s, StagedAccountSection real, StagedAccountSection copy, List<Difference> out) {
        DetectedAccountInfo a = real.detectedAccount();
        DetectedAccountInfo b = copy.detectedAccount();
        if (a != null || b != null) {
            if (a == null || b == null) {
                // One reading found the account's details and the other found none: that is the
                // difference, not any one of the fields inside them.
                out.add(new Difference(s, null, Field.DETECTED_ACCOUNT, List.of()));
            } else {
                section(s, Field.PERIOD_START, a.statementPeriodStart(), b.statementPeriodStart(), out);
                section(s, Field.PERIOD_END, a.statementPeriodEnd(), b.statementPeriodEnd(), out);
                money(s, null, Field.OPENING_BALANCE, a.openingBalance(), b.openingBalance(), out);
                money(s, null, Field.CLOSING_BALANCE, a.closingBalance(), b.closingBalance(), out);
            }
        }
        if (size(real.unparseableRows()) != size(copy.unparseableRows())) {
            out.add(new Difference(s, null, Field.UNPARSEABLE_COUNT, List.of()));
        }
        if (!findingOutcomes(real).equals(findingOutcomes(copy))) {
            out.add(new Difference(s, null, Field.FINDINGS, List.of()));
        }
        List<StagedRow> ra = real.rows() == null ? List.of() : real.rows();
        List<StagedRow> rb = copy.rows() == null ? List.of() : copy.rows();
        if (ra.size() != rb.size()) out.add(new Difference(s, null, Field.ROW_COUNT, List.of()));
        for (int r = 0; r < Math.min(ra.size(), rb.size()); r++) {
            compareRow(s, r, ra.get(r), rb.get(r), out);
        }
    }

    private static void compareRow(int s, int r, StagedRow a, StagedRow b, List<Difference> out) {
        if (!Objects.equals(a.date(), b.date())) out.add(new Difference(s, r, Field.DATE, List.of()));
        money(s, r, Field.AMOUNT, a.amount(), b.amount(), out);
        if (!Objects.equals(a.type(), b.type())) out.add(new Difference(s, r, Field.DIRECTION, List.of()));
        money(s, r, Field.BALANCE, a.balanceAfter(), b.balanceAfter(), out);
        if (!sameDescription(a.description(), b.description())) {
            out.add(new Difference(s, r, Field.DESCRIPTION, maskedTokens(b.description())));
        }
    }

    /**
     * Whether the copy's description is what the redactor would have written for the real one.
     *
     * <p>The copy masks each run on its own; the real description is one joined line. Where the
     * two groupings of text into tokens agree, the copy's description is exactly
     * {@link PdfTraceRedactor#redactLine} of the real one, and that is checked first. Where a token
     * only forms across a line break or a run boundary, the two mask it differently, which is not a
     * difference in what was read. So, failing exact equality, the two are compared token by token
     * (a token is a run of non-whitespace):
     *
     * <ul>
     *   <li>the whitespace between tokens must be identical, and the token counts equal;</li>
     *   <li>each copy token must be ONE of: (a) the joined line's masking of that token; (b) the
     *       real token itself, only where the joined line masked it AND the token is digits and
     *       punctuation with no letter or mark -- the copy kept the run's text verbatim (two kept
     *       amounts printed with no space between form a long digit run only when joined; that
     *       digit-run artifact is the one measured, so a token holding letters in clear does not
     *       count); (c) the whole token masked character by
     *       character ({@link PdfTraceRedactor#maskOf}), only where the real token is digits and
     *       punctuation with no letter or mark, or is exactly a month name -- a date split across a
     *       line break is kept joined and masked in pieces. A token mixing letters and digits (such
     *       as "UPI/1234/PAID" or "PAID-1,234.00") is never masked whole; (d) the real token cut
     *       at every '/' and '-', each piece masked by {@link PdfTraceRedactor#redactLine} on its
     *       own, separators kept -- a digit run split across runs reaches eight digits only when
     *       joined, so the joined line masks the whole compound, kept words included, while the
     *       copy masked each run and kept them. This is one exact masking of the real text, not a
     *       tolerance: a different word, a different length or a word in clear differs.</li>
     * </ul>
     *
     * <p>Guaranteed: a missing, extra or reordered line, or a word grouped into another row, moves a
     * token or a space and differs; a word the joined line keeps can never be masked in the copy
     * unless it holds a digit, names a month, or is one piece of a compound the redactor masks
     * piece by piece under (d); no piece between separators may be partly kept and partly masked,
     * and a letter appears in clear only where the redactor keeps it, in the joined line or, under
     * (d), in its own piece.
     *
     * <p>Not guaranteed (known limits, pinned by tests): under (c), a token of digits and
     * punctuation that the copy masked whole matches any same-shape real token at that position, so
     * a different amount or date of the same shape there is not seen. And a token glued from a run the copy kept and a run it
     * masked is reported as a difference although it was read the same way.
     */
    static boolean sameDescription(String real, String copy) {
        if (real == null || copy == null) return real == copy;
        String joined = PdfTraceRedactor.redactLine(real);
        if (copy.equals(joined)) return true;

        List<String> r = parts(real);
        List<String> c = parts(copy);
        List<String> j = parts(joined);
        if (r.size() != c.size()) return false;
        if (j.size() != r.size()) {
            // The joined line's masking does not line up with the real text token for token:
            // compare against the masking alone, which exact equality above already did.
            return false;
        }
        for (int i = 0; i < r.size(); i++) {
            String realPart = r.get(i);
            String copyPart = c.get(i);
            if (isWhitespace(realPart) || isWhitespace(copyPart)) {
                if (!copyPart.equals(realPart)) return false;
                continue;
            }
            String joinedToken = j.get(i);
            if (copyPart.equals(joinedToken)) continue;                                 // (a)
            if (!joinedToken.equals(realPart) && isDigitsAndPunctuation(realPart)
                    && copyPart.equals(realPart)) continue;                             // (b)
            if ((isDigitsAndPunctuation(realPart) || isMonthName(realPart))
                    && copyPart.equals(maskWhole(realPart))) continue;                  // (c)
            if (copyPart.equals(maskPieceByPiece(realPart))) continue;                  // (d)
            return false;
        }
        return true;
    }

    private static final Pattern PART = Pattern.compile("\\s+|\\S+");
    private static final Pattern MONTH_NAME = Pattern.compile(
            "(?i)jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(tember)?"
                    + "|oct(ober)?|nov(ember)?|dec(ember)?");

    /** The text as alternating runs of whitespace and of non-whitespace, in order. */
    private static List<String> parts(String text) {
        List<String> out = new ArrayList<>();
        var m = PART.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static boolean isWhitespace(String part) {
        return !part.isEmpty() && Character.isWhitespace(part.codePointAt(0));
    }

    private static boolean hasDigit(String token) {
        return token.codePoints().anyMatch(Character::isDigit);
    }

    /** A digit, and nothing that is a letter or a mark: a number, a date, an amount. */
    private static boolean isDigitsAndPunctuation(String token) {
        return hasDigit(token) && token.codePoints().noneMatch(cp -> Character.isLetter(cp)
                || Character.getType(cp) == Character.NON_SPACING_MARK
                || Character.getType(cp) == Character.COMBINING_SPACING_MARK
                || Character.getType(cp) == Character.ENCLOSING_MARK);
    }

    private static boolean isMonthName(String token) {
        return MONTH_NAME.matcher(token).matches();
    }

    /** The token cut at every '/' and '-', each piece masked by {@link PdfTraceRedactor#redactLine}
     *  on its own, and joined again with the same separators. */
    private static String maskPieceByPiece(String token) {
        StringBuilder out = new StringBuilder(token.length());
        int start = 0;
        for (int i = 0; i <= token.length(); i++) {
            if (i == token.length() || token.charAt(i) == '/' || token.charAt(i) == '-') {
                out.append(PdfTraceRedactor.redactLine(token.substring(start, i)));
                if (i < token.length()) out.append(token.charAt(i));
                start = i + 1;
            }
        }
        return out.toString();
    }

    /** Every letter, number and mark of the token masked as the redactor masks it; punctuation kept. */
    private static String maskWhole(String token) {
        StringBuilder out = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> out.appendCodePoint(PdfTraceRedactor.maskOf(cp)));
        return out.toString();
    }

    /** The copy's own tokens that were masked: they hold an X, x or 9 run the redactor wrote. */
    private static List<String> maskedTokens(String copyDescription) {
        if (copyDescription == null) return List.of();
        return Arrays.stream(copyDescription.split("\\s+"))
                .filter(t -> t.chars().anyMatch(Character::isLetterOrDigit))
                .filter(t -> t.chars().allMatch(c -> c == 'X' || c == 'x' || c == '9' || !Character.isLetterOrDigit(c)))
                .toList();
    }

    private static void section(int s, Field field, Object a, Object b, List<Difference> out) {
        if (!Objects.equals(a, b)) out.add(new Difference(s, null, field, List.of()));
    }

    private static void money(int s, Integer r, Field field, BigDecimal a, BigDecimal b, List<Difference> out) {
        boolean same = a == null ? b == null : b != null && a.compareTo(b) == 0;
        if (!same) out.add(new Difference(s, r, field, List.of()));
    }

    private static List<String> findingOutcomes(StagedAccountSection section) {
        if (section.verification() == null || section.verification().findings() == null) return List.of();
        return section.verification().findings().stream()
                .map(f -> f.rule() + "=" + f.outcome()).sorted().toList();
    }

    private static int size(List<?> list) { return list == null ? 0 : list.size(); }
}
