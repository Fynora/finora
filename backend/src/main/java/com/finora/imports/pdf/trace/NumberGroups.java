package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the digits on a printed line group into numbers. Shared by {@link PdfTraceRedactor}, which
 * decides from it which digits it may keep, and by {@link TraceValidator#privacyKinds}, which uses
 * the same reading of the redacted text as a fail-closed backstop. One model, so the two cannot
 * disagree about what a number is.
 *
 * <p>A <b>unit</b> is a value reading needs and the policy keeps: a date (numeric with parts that can
 * be a day, a month and a year; or with a month name), an HH:MM time, a decimal amount, or a
 * comma-grouped amount of at most {@value #MAX_AMOUNT_INTEGER_DIGITS} integer digits, optionally
 * with a glued rupee prefix or Cr/Dr. A unit counts only when it stands apart: not glued to a word
 * character, and not glued through one punctuation character to a digit outside another unit.
 *
 * <p>A <b>group</b> is any other run of ASCII digits. Units and groups <b>chain</b> when one to
 * {@value #MAX_SEPARATOR} characters with no word character separate them (a space of any width, a
 * dash, a slash, a colon, an underscore, a dot, a comma, a double space). A chain holding a group
 * and {@value #LONG_DIGITS} or more digits in all is one number -- a mobile, card, account or
 * Aadhaar number printed in pieces -- and its groups are {@link Kind#LONG}.
 */
final class NumberGroups {

    private NumberGroups() {}

    enum Kind { NONE, UNIT, SHORT, LONG }

    static final int LONG_DIGITS = 6;
    static final int MAX_SEPARATOR = 3;
    static final int MAX_AMOUNT_INTEGER_DIGITS = 8;
    /** Runs whose y differ by at most this many points are one printed line. */
    static final float LINE_Y_TOLERANCE = 1.5f;
    /** Neighbouring runs on a line join when the gap is at most this many average characters of
     *  the left run (its width over its length): a space or two, never a column gap. */
    static final float GAP_CHARACTERS = 3f;
    /**
     * Extra points the redactor adds to that gap. A changed run's x and width are rounded to a
     * whole point in the copy, which moves a gap by up to 1.5pt and the character width by up to
     * half a point per character; 3pt covers both, so the redactor always joins at least the runs
     * the backstop joins when it reads the copy, and never keeps digits the backstop then refuses.
     */
    static final float REDACTOR_GAP_MARGIN = 3f;

    private static final String NUMBER =
            "(?:\\d{1,3}(?:,\\d{2})*,\\d{3}|\\d{1,3}(?:,\\d{3})+|\\d+)";
    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "(\\d{1,2})[-/.](\\d{1,2})[-/.]\\d{2,4}|(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");
    /** An amount never starts or ends inside a longer grouping: "9,876" is not an amount inside
     *  "9,876,543,210", and "9876.54" is not one inside "9876.54.3210". */
    private static final String NOT_INSIDE_BEFORE = "(?<!\\d[,.])";
    private static final String NOT_INSIDE_AFTER = "(?![,.]\\d)";
    private static final Pattern DECIMAL_AMOUNT = Pattern.compile(
            "(?i:rs\\.?|inr\\.?)?\\(?-?" + NOT_INSIDE_BEFORE + "(" + NUMBER + ")\\.\\d{1,2}" + NOT_INSIDE_AFTER
                    + "\\)?(?i:cr|dr)?");
    private static final Pattern COMMA_AMOUNT = Pattern.compile(
            "(?i:rs\\.?|inr\\.?)?\\(?-?" + NOT_INSIDE_BEFORE
                    + "(\\d{1,3}(?:,\\d{2})*,\\d{3}|\\d{1,3}(?:,\\d{3})+)" + NOT_INSIDE_AFTER + "\\)?(?i:cr|dr)?");

    /** A numeric date whose parts can be a day, a month and a year (either day-month or
     *  month-day), or a year-month-day. */
    static boolean isRealNumericDate(String text) {
        Matcher m = NUMERIC_DATE.matcher(text);
        if (!m.matches()) return false;
        if (m.group(1) != null) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return a >= 1 && b >= 1 && ((a <= 31 && b <= 12) || (a <= 12 && b <= 31));
        }
        int month = Integer.parseInt(m.group(4));
        int day = Integer.parseInt(m.group(5));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31;
    }

    /** A letter, digit, mark, private-use or format character: anything that can belong to a word.
     *  Never a separator, and a unit glued to one is not standing apart. */
    static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || PdfTraceRedactor.isTextCodePoint(cp)
                || Character.getType(cp) == Character.FORMAT;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Per char of {@code text}: which digits are in a unit, a short chain or a long one. */
    static Kind[] scan(String text) {
        return scan(text, false);
    }

    /**
     * @param maskedAware a group made only of '9' is the redactor's mask, not a number in clear: it
     *                    takes no part in a chain (the backstop reads redacted text).
     */
    static Kind[] scan(String text, boolean maskedAware) {
        int n = text.length();
        Kind[] kinds = new Kind[n];
        java.util.Arrays.fill(kinds, Kind.NONE);
        boolean[] unit = units(text);
        // Elements in order: units and free groups, as [start, end, isUnit].
        List<int[]> elements = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (unit[i]) {
                int s = i;
                while (i < n && unit[i]) i++;
                elements.add(new int[] {s, i, 1});
            } else if (isDigit(text.charAt(i))) {
                int s = i;
                while (i < n && isDigit(text.charAt(i)) && !unit[i]) i++;
                if (!(maskedAware && text.substring(s, i).chars().allMatch(c -> c == '9'))) {
                    elements.add(new int[] {s, i, 0});
                }
            } else {
                i++;
            }
        }
        int e = 0;
        while (e < elements.size()) {
            int end = e + 1;
            while (end < elements.size() && links(text, elements.get(end - 1)[1], elements.get(end)[0])) end++;
            int digits = 0;
            boolean hasGroup = false;
            for (int k = e; k < end; k++) {
                int[] el = elements.get(k);
                for (int c = el[0]; c < el[1]; c++) if (isDigit(text.charAt(c))) digits++;
                if (el[2] == 0) hasGroup = true;
            }
            Kind groupKind = hasGroup && digits >= LONG_DIGITS ? Kind.LONG : Kind.SHORT;
            for (int k = e; k < end; k++) {
                int[] el = elements.get(k);
                for (int c = el[0]; c < el[1]; c++) {
                    if (isDigit(text.charAt(c))) kinds[c] = el[2] == 1 ? Kind.UNIT : groupKind;
                }
            }
            e = end;
        }
        return kinds;
    }

    /** One to {@value #MAX_SEPARATOR} characters, none of them a word character. */
    private static boolean links(String text, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_SEPARATOR) return false;
        for (int c = from; c < to; c++) if (isWordChar(text.charAt(c))) return false;
        return true;
    }

    /** Which chars belong to a unit that stands apart. */
    static boolean[] units(String text) {
        List<int[]> candidates = new ArrayList<>();
        addMatches(candidates, PdfTraceRedactor.SPACED_NAMED_MONTH_DATE_LIKE.matcher(text), text, null);
        addMatches(candidates, PdfTraceRedactor.NAMED_MONTH_DATE_LIKE.matcher(text), text, null);
        addMatches(candidates, PdfTraceRedactor.GLUED_DAY_MONTH_DATE_LIKE.matcher(text), text, null);
        addMatches(candidates, NUMERIC_DATE.matcher(text), text, NumberGroups::isRealNumericDate);
        addMatches(candidates, TIME.matcher(text), text, null);
        addMatches(candidates, DECIMAL_AMOUNT.matcher(text), text, NumberGroups::withinIntegerCap);
        addMatches(candidates, COMMA_AMOUNT.matcher(text), text, NumberGroups::withinIntegerCap);
        candidates.sort(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> -c[1]));
        List<int[]> accepted = new ArrayList<>();
        int lastEnd = -1;
        for (int[] c : candidates) {
            if (c[0] >= lastEnd) {
                accepted.add(c);
                lastEnd = c[1];
            }
        }
        boolean[] in = new boolean[text.length()];
        for (int[] c : accepted) for (int k = c[0]; k < c[1]; k++) in[k] = true;
        // A unit glued through one punctuation character to a digit outside any unit is part of a
        // longer number, not a value: drop it, and repeat until nothing changes.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int[] c : accepted) {
                if (!in[c[0]]) continue;
                if (gluedToFreeDigit(text, in, c[0] - 1, -1) || gluedToFreeDigit(text, in, c[1], 1)) {
                    for (int k = c[0]; k < c[1]; k++) in[k] = false;
                    changed = true;
                }
            }
        }
        return in;
    }

    private static boolean gluedToFreeDigit(String text, boolean[] in, int p, int step) {
        int q = p + step;
        if (p < 0 || p >= text.length() || q < 0 || q >= text.length()) return false;
        char c = text.charAt(p);
        return !isSpace(c) && !isWordChar(c) && isDigit(text.charAt(q)) && !in[q];
    }

    private static void addMatches(List<int[]> out, Matcher m, String text, java.util.function.Predicate<String> valid) {
        while (m.find()) {
            int s = m.start();
            int e = m.end();
            if (s == e) continue;
            if (s > 0 && isWordChar(text.codePointBefore(s))) continue;
            if (e < text.length() && isWordChar(text.codePointAt(e))) continue;
            if (valid != null && !valid.test(m.group())) continue;
            out.add(new int[] {s, e});
        }
    }

    private static boolean withinIntegerCap(String amount) {
        String integer = amount.replaceAll("(?i)^(?:rs\\.?|inr\\.?)", "").replaceAll("\\.\\d{1,2}\\)?(?i:cr|dr)?$", "");
        return integer.chars().filter(c -> c >= '0' && c <= '9').count() <= MAX_AMOUNT_INTEGER_DIGITS;
    }

    // ---------------------------------------------------------------- printed lines

    /** One printed line: its runs left to right, joined into one string, and where each char came
     *  from ({run index, char index}, or null for a joiner). */
    record Line(String text, List<int[]> origin) {}

    /** How each run's digits group on its line, and the line chars just before and after the run
     *  ('\0' at the line's edge), so a token at a run's edge is judged in its line. */
    record Scan(Kind[][] kinds, char[] before, char[] after) {}

    /**
     * Runs on one page whose y is within {@value #LINE_Y_TOLERANCE}pt of the line's first run, read
     * left to right. Neighbours join when the gap between them is at most
     * {@value #GAP_CHARACTERS} average characters of the left run (of the right one when the left
     * has no width), or when neither has a measured width -- joining more is the safe side. A join
     * adds one space unless either side already ends or starts with a non-word character; runs
     * that do not join are separated by four spaces, more than a separator, so nothing chains
     * across them.
     */
    static List<Line> lines(List<PositionedText> runs) {
        return lines(runs, 0f);
    }

    static List<Line> lines(List<PositionedText> runs, float gapMargin) {
        List<Integer> order = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            String text = runs.get(r).text();
            if (text != null && !text.isEmpty()) order.add(r);
        }
        order.sort(Comparator.<Integer>comparingInt(r -> runs.get(r).pageIndex())
                .thenComparingDouble(r -> runs.get(r).y()));
        List<Line> lines = new ArrayList<>();
        int k = 0;
        while (k < order.size()) {
            PositionedText first = runs.get(order.get(k));
            int end = k;
            while (end < order.size() && runs.get(order.get(end)).pageIndex() == first.pageIndex()
                    && Math.abs(runs.get(order.get(end)).y() - first.y()) <= LINE_Y_TOLERANCE) end++;
            List<Integer> line = new ArrayList<>(order.subList(k, end));
            line.sort(Comparator.comparingDouble(r -> runs.get(r).x()));
            lines.add(join(runs, line, gapMargin));
            k = end;
        }
        return lines;
    }

    private static Line join(List<PositionedText> runs, List<Integer> line, float gapMargin) {
        StringBuilder text = new StringBuilder();
        List<int[]> origin = new ArrayList<>();
        for (int n = 0; n < line.size(); n++) {
            PositionedText run = runs.get(line.get(n));
            if (n > 0) {
                PositionedText left = runs.get(line.get(n - 1));
                String joiner;
                if (!adjacent(left, run, gapMargin)) joiner = "    ";   // more than MAX_SEPARATOR: nothing chains across
                else if (!isWordChar(left.text().codePointBefore(left.text().length()))
                        || !isWordChar(run.text().codePointAt(0))) joiner = "";
                else joiner = " ";
                for (int c = 0; c < joiner.length(); c++) { text.append(joiner.charAt(c)); origin.add(null); }
            }
            String t = run.text();
            for (int c = 0; c < t.length(); c++) { text.append(t.charAt(c)); origin.add(new int[] {line.get(n), c}); }
        }
        return new Line(text.toString(), origin);
    }

    private static boolean adjacent(PositionedText left, PositionedText right, float gapMargin) {
        float charWidth = averageCharWidth(left);
        if (charWidth <= 0f) charWidth = averageCharWidth(right);
        if (charWidth <= 0f) return true;
        float leftEnd = left.width() > 0f ? left.endX() : left.x();
        return right.x() - leftEnd <= GAP_CHARACTERS * charWidth + gapMargin;
    }

    private static float averageCharWidth(PositionedText run) {
        if (run.width() <= 0f) return 0f;
        return run.width() / Math.max(1, run.text().codePointCount(0, run.text().length()));
    }

    /** {@link #scan} over each printed line (joined with the redactor's margin), mapped back to
     *  each run's chars, with each run's neighbouring line chars. */
    static Scan scanByLine(List<PositionedText> runs) {
        Kind[][] kinds = new Kind[runs.size()][];
        char[] before = new char[runs.size()];
        char[] after = new char[runs.size()];
        for (int r = 0; r < runs.size(); r++) {
            String text = runs.get(r).text();
            kinds[r] = new Kind[text == null ? 0 : text.length()];
            java.util.Arrays.fill(kinds[r], Kind.NONE);
        }
        for (Line line : lines(runs, REDACTOR_GAP_MARGIN)) {
            Kind[] lineKinds = scan(line.text());
            for (int p = 0; p < lineKinds.length; p++) {
                int[] o = line.origin().get(p);
                if (o == null) continue;
                kinds[o[0]][o[1]] = lineKinds[p];
                if (o[1] == 0) before[o[0]] = p > 0 ? line.text().charAt(p - 1) : '\0';
                if (o[1] == runs.get(o[0]).text().length() - 1) {
                    after[o[0]] = p + 1 < line.text().length() ? line.text().charAt(p + 1) : '\0';
                }
            }
        }
        return new Scan(kinds, before, after);
    }
}
