package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Line breaks inside a wrapped narration, kept through row formation and resolved once per document.
 *
 * <p>Banks wrap a narration by width, and some wrap at a fixed character count straight through a
 * UPI id, an IFSC or a UTR. Joining the fragments with a hard space at merge time throws away the one
 * fact that decides whether the break was a real space: where the printed line ended. The join sites
 * in {@link PdfTableLocator} write {@link #MARK} instead, and {@link #resolveAll} decides each break
 * once every row of the document is known. A break with no evidence stays one space, exactly as
 * before.
 *
 * <p>Evidence, measured on the real corpus (never a guess about a bank):
 * <ul>
 *   <li><b>Handle</b> -- a line ending in {@code @}, or the next starting with it. Across the corpus the
 *       text layer never prints a space beside {@code @}; every such break is a wrap.</li>
 *   <li><b>Separator</b> -- a line ending in {@code - / . _} and the next line's first token carrying a
 *       digit or {@code @}: a field separator followed by an identifier. It never fired on a break the
 *       text layer shows printed with a space.</li>
 *   <li><b>Character width</b> -- a document that prints its narration in fixed-width lines (the HDFC
 *       family: 40 characters, splitting words and identifiers alike). Its pieces often arrive cut at a
 *       real space INSIDE a line too, so the width is found by rebuilding lines from piece lengths, and
 *       only a break where a line is exactly full is a wrap. A line one short of the width ended at a
 *       space. A full line glues when the joined segment carries a digit or either side is a separator;
 *       two plain words keep the space, since nothing says which it was.</li>
 * </ul>
 */
final class NarrationLineBreaks {

    static final char MARK = '\n';

    /** Evaluated widths, and the bar a width must clear before it is trusted. */
    private static final int MIN_WIDTH = 20;
    private static final int MAX_WIDTH = 120;
    private static final double MIN_CONSISTENT_SHARE = 0.85;
    private static final int MIN_FULL_LINE_BREAKS = 10;

    /** A line separator a PDF text run can carry inside one cell (a real Standard Chartered export
     *  prints its narration lines with {@code \r}), with the blanks around it. Stored verbatim it
     *  broke merchant extraction on every row of that document; it is a line break like any other. */
    private static final Pattern CONTROL_LINE_BREAK = Pattern.compile("[ \\t]*[\\r\\u0085\\u2028\\u2029][ \\t]*");

    private static final String SEPARATORS = "-/._";
    private static final Pattern SEGMENT_BOUNDARY = Pattern.compile("[-/@._:\\s]");
    private static final Pattern ENDS_WITH_DATE = Pattern.compile(".*\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}$");
    private static final Pattern STARTS_WITH_TIME = Pattern.compile("^\\d{1,2}:\\d{2}.*");

    enum Rule { HANDLE, SEPARATOR, CHARACTER_WIDTH }

    private enum Boundary { FULL_LINE, SHORT_LINE, INSIDE_LINE }

    private NarrationLineBreaks() {}

    /** {@code earlier} was printed on the line above {@code later}. */
    static String joinLines(String earlier, String later) {
        return earlier + MARK + later;
    }

    static PdfTableLocator.LocatedDocument resolveAll(PdfTableLocator.LocatedDocument doc, DocumentContext ctx) {
        List<List<String>> cells = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            for (Map<String, String> row : section.rows()) {
                for (String v : row.values()) {
                    String n = withControlBreaksAsMarks(v);
                    if (n != null && n.indexOf(MARK) >= 0) cells.add(piecesOf(n));
                }
            }
        }
        Integer width = characterWrapWidth(cells);
        Set<Rule> fired = EnumSet.noneOf(Rule.class);
        boolean controlBreaks = false;
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            for (Map<String, String> row : section.rows()) {
                for (String v : row.values()) {
                    if (v != null && CONTROL_LINE_BREAK.matcher(v).find()) controlBreaks = true;
                }
            }
        }

        List<PdfTableLocator.LocatedSection> sections = new ArrayList<>(doc.sections().size());
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            List<Map<String, String>> rows = new ArrayList<>(section.rows().size());
            for (Map<String, String> row : section.rows()) {
                rows.add(resolveRow(row, width, fired));
            }
            sections.add(new PdfTableLocator.LocatedSection(section.auxiliaryText(), rows, section.evidence()));
        }
        if (ctx != null) {
            if (controlBreaks) ctx.record("NARRATION_CONTROL_CHARACTER_AS_LINE_BREAK");
            if (width != null) ctx.record("NARRATION_CHARACTER_WRAP_WIDTH_DETECTED");
            if (fired.contains(Rule.HANDLE)) ctx.record("NARRATION_WRAP_JOINED_AT_HANDLE");
            if (fired.contains(Rule.SEPARATOR)) ctx.record("NARRATION_WRAP_JOINED_AT_SEPARATOR");
            if (fired.contains(Rule.CHARACTER_WIDTH)) ctx.record("NARRATION_WRAP_JOINED_AT_CHARACTER_WIDTH");
        }
        return new PdfTableLocator.LocatedDocument(sections, doc.physicalRowFormationEvidence());
    }

    private static Map<String, String> resolveRow(Map<String, String> row, Integer width, Set<Rule> fired) {
        boolean anyBreak = false;
        for (String v : row.values()) {
            String n = withControlBreaksAsMarks(v);
            if (n != null && n.indexOf(MARK) >= 0) { anyBreak = true; break; }
        }
        if (!anyBreak) return row;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> cell : row.entrySet()) {
            String n = withControlBreaksAsMarks(cell.getValue());
            resolved.put(cell.getKey(), n == null || n.indexOf(MARK) < 0 ? n : resolveCell(piecesOf(n), width, fired));
        }
        return resolved;
    }

    /** A control line break that ends a text run is often followed by a join's own break: that is
     *  one printed line end, so a run of breaks with only blanks between them collapses to one. */
    private static final Pattern REPEATED_BREAKS = Pattern.compile("\n(?:[ \t]*\n)+[ \t]*");

    private static String withControlBreaksAsMarks(String v) {
        if (v == null || !CONTROL_LINE_BREAK.matcher(v).find()) return v;
        String marked = CONTROL_LINE_BREAK.matcher(v).replaceAll(String.valueOf(MARK));
        return REPEATED_BREAKS.matcher(marked).replaceAll(String.valueOf(MARK));
    }

    private static List<String> piecesOf(String v) {
        return Arrays.asList(v.split(String.valueOf(MARK), -1));
    }

    static String resolveCell(List<String> pieces, Integer width) {
        return resolveCell(pieces, width, EnumSet.noneOf(Rule.class));
    }

    private static String resolveCell(List<String> pieces, Integer width, Set<Rule> fired) {
        List<Boundary> boundaries = width == null ? null : boundaries(trimmedLengths(pieces), width);
        StringBuilder out = new StringBuilder(pieces.get(0));
        for (int i = 1; i < pieces.size(); i++) {
            String line = pieces.get(i - 1).strip();
            String next = pieces.get(i).strip();
            Rule rule = null;
            if (boundaries == null) {
                rule = evidenceRule(line, next);
            } else if (boundaries.get(i - 1) == Boundary.FULL_LINE) {
                rule = evidenceRule(line, next);
                if (rule == null && glueAtFullLine(line, next)) rule = Rule.CHARACTER_WIDTH;
            }
            // A break the rebuilt lines place inside a line, or at a line one short of the width, was
            // printed as a space; no rule overrides it.
            if (rule != null && !line.isEmpty() && !next.isEmpty()) {
                fired.add(rule);
                int end = out.length();
                while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) end--;
                out.setLength(end);
                out.append(pieces.get(i).stripLeading());
            } else {
                out.append(' ').append(pieces.get(i));
            }
        }
        return out.toString();
    }

    /** G2 and G3: evidence that holds on any document, width or not. */
    static boolean glueByEvidence(String line, String next) {
        return evidenceRule(line, next) != null;
    }

    private static Rule evidenceRule(String line, String next) {
        if (line.isEmpty() || next.isEmpty()) return null;
        char last = line.charAt(line.length() - 1);
        char first = next.charAt(0);
        if (last == '@' || first == '@') return Rule.HANDLE;
        String headToken = next.split("\\s+", 2)[0];
        if (SEPARATORS.indexOf(last) >= 0 && Character.isLetterOrDigit(first)
                && headToken.chars().anyMatch(c -> Character.isDigit(c) || c == '@')) {
            return Rule.SEPARATOR;
        }
        return null;
    }

    /** G1, at a line the rebuilt layout shows is exactly full. */
    private static boolean glueAtFullLine(String line, String next) {
        if (ENDS_WITH_DATE.matcher(line).matches() && STARTS_WITH_TIME.matcher(next).matches()) return false;
        char last = line.charAt(line.length() - 1);
        char first = next.charAt(0);
        if (SEPARATORS.indexOf(last) >= 0 || SEPARATORS.indexOf(first) >= 0) return true;
        if (!Character.isLetterOrDigit(last) || !Character.isLetterOrDigit(first)) return false;
        // A handle cut mid-word ("...@PTAX" / "IS-..."): nothing after the '@' has ended it yet, and a
        // handle never carries a space.
        int at = line.lastIndexOf('@');
        if (at >= 0 && at < line.length() - 1 && !SEGMENT_BOUNDARY.matcher(line.substring(at + 1)).find()) return true;
        return (lastSegment(line) + firstSegment(next)).chars().anyMatch(Character::isDigit);
    }

    /**
     * The document's fixed narration width, or null when its narration is not printed in fixed-width
     * lines. Every width from {@value #MIN_WIDTH} to {@value #MAX_WIDTH} is tried: the pieces of each
     * multi-line cell (all but the last, which may end anywhere) are laid back into lines of that
     * width, a piece joining the current line when it fits after one space and starting a new line
     * when the current one is full (or one short: its last character was a space). A width is
     * trusted when at least {@value #MIN_CONSISTENT_SHARE} of cells lay out without overflowing and
     * at least {@value #MIN_FULL_LINE_BREAKS} breaks fall on a full line; of those, the one with the
     * most full-line breaks wins. Measured: the three large HDFC statements give 40 (93%, 93%, 91%
     * consistent); no other document in the corpus clears the bar at any width.
     */
    static Integer characterWrapWidth(List<List<String>> cells) {
        List<int[]> candidates = new ArrayList<>();
        for (List<String> cell : cells) {
            if (cell.size() >= 3) candidates.add(trimmedLengths(cell.subList(0, cell.size() - 1)));
        }
        if (candidates.isEmpty()) return null;
        Integer best = null;
        int bestFull = -1;
        for (int w = MIN_WIDTH; w <= MAX_WIDTH; w++) {
            int consistent = 0, full = 0;
            for (int[] lengths : candidates) {
                List<Boundary> b = boundaries(lengths, w);
                if (b == null) continue;
                consistent++;
                for (Boundary x : b) if (x == Boundary.FULL_LINE) full++;
            }
            if (consistent >= MIN_CONSISTENT_SHARE * candidates.size() && full >= MIN_FULL_LINE_BREAKS && full > bestFull) {
                best = w;
                bestFull = full;
            }
        }
        return best;
    }

    /** Where each break falls when the pieces are laid into lines of {@code width}; null on overflow. */
    private static List<Boundary> boundaries(int[] lengths, int width) {
        List<Boundary> out = new ArrayList<>(lengths.length);
        int acc = lengths[0];
        if (acc > width) return null;
        for (int i = 1; i < lengths.length; i++) {
            if (acc == width) { out.add(Boundary.FULL_LINE); acc = lengths[i]; }
            else if (acc == width - 1) { out.add(Boundary.SHORT_LINE); acc = lengths[i]; }
            else if (acc + 1 + lengths[i] <= width) { out.add(Boundary.INSIDE_LINE); acc += 1 + lengths[i]; }
            else return null;
            if (acc > width) return null;
        }
        return out;
    }

    private static int[] trimmedLengths(List<String> pieces) {
        int[] out = new int[pieces.size()];
        for (int i = 0; i < out.length; i++) out[i] = pieces.get(i).strip().length();
        return out;
    }

    private static String lastSegment(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (SEGMENT_BOUNDARY.matcher(String.valueOf(s.charAt(i))).matches()) return s.substring(i + 1);
        }
        return s;
    }

    private static String firstSegment(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (SEGMENT_BOUNDARY.matcher(String.valueOf(s.charAt(i))).matches()) return s.substring(0, i);
        }
        return s;
    }
}
