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
 *   <li><b>IFSC</b> -- the line's last field and the next line's first field together make exactly
 *       one IFSC (four letters, a zero, six letters or digits), each side delimited by a field
 *       separator or the cell's edge; or the line ends in a whole IFSC and the next line continues
 *       with the separator that came before it. An IFSC is never printed with a space inside it, and
 *       banks that wrap by width cut straight through it: measured on the real slice small finance
 *       bank statement (a proportional font, so no character width applies), where 9 of its 19
 *       breaks fall inside or right after an IFSC; and on a real HDFC statement, where 32 IFSCs cut
 *       after their sixth character were still joined with a space by every other rule here. It
 *       holds at every break, including one the character-width rule reads as a printed space.</li>
 *   <li><b>Character width</b> -- a document that prints its narration in fixed-width lines (the HDFC
 *       family: 40 characters, splitting words and identifiers alike). Its pieces often arrive cut at a
 *       real space INSIDE a line too, so the width is found by rebuilding lines from piece lengths, and
 *       only a break where a line is exactly full is a wrap. A line one short of the width ended at a
 *       space. A full line glues when the joined segment carries a digit or either side is a separator;
 *       two plain words keep the space, since nothing says which it was.</li>
 *   <li><b>Printed line ends</b> -- a text layer that ends its narration lines with a line separator
 *       (the real Standard Chartered export ends each field's line with {@code \r}). There a line
 *       that ends in neither that separator nor a blank was wrapped inside a word, and a line ending
 *       in a blank was wrapped between words. Measured on that export: all 69 breaks of the first
 *       kind fell inside a word or an identifier (a UPI id, an IFSC), all 51 of the second between
 *       words. A document with no such separator anywhere gives no such evidence and is unchanged.</li>
 * </ul>
 */
final class NarrationLineBreaks {

    static final char MARK = '\n';

    /** Where a control line separator was, after {@link #withControlBreaksAsMarks}: a line end the
     *  text layer printed itself, as opposed to a {@link #MARK} a join wrote. Internal only; every
     *  resolved cell has it replaced. */
    private static final char PRINTED_END = '\u001E';
    private static final Pattern ANY_BREAK = Pattern.compile("[\n\u001E]");

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

    enum Rule { HANDLE, SEPARATOR, IFSC, CHARACTER_WIDTH, WITHOUT_PRINTED_SPACE }

    private static final Pattern IFSC = Pattern.compile("[A-Z]{4}0[A-Z0-9]{6}");
    /** What may stand right before or after an IFSC field: the narration's own field separators. */
    private static final String IFSC_FIELD_SEPARATORS = "-/";

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
                    if (hasBreak(n)) cells.add(piecesOf(n));
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
                rows.add(resolveRow(row, width, controlBreaks, fired));
            }
            sections.add(new PdfTableLocator.LocatedSection(section.auxiliaryText(), rows, section.evidence()));
        }
        if (ctx != null) {
            if (controlBreaks) ctx.record("NARRATION_CONTROL_CHARACTER_AS_LINE_BREAK");
            if (width != null) ctx.record("NARRATION_CHARACTER_WRAP_WIDTH_DETECTED");
            if (fired.contains(Rule.HANDLE)) ctx.record("NARRATION_WRAP_JOINED_AT_HANDLE");
            if (fired.contains(Rule.SEPARATOR)) ctx.record("NARRATION_WRAP_JOINED_AT_SEPARATOR");
            if (fired.contains(Rule.IFSC)) ctx.record("NARRATION_WRAP_JOINED_AT_IFSC");
            if (fired.contains(Rule.CHARACTER_WIDTH)) ctx.record("NARRATION_WRAP_JOINED_AT_CHARACTER_WIDTH");
            if (fired.contains(Rule.WITHOUT_PRINTED_SPACE)) ctx.record("NARRATION_WRAP_JOINED_WITHOUT_PRINTED_SPACE");
        }
        return new PdfTableLocator.LocatedDocument(sections, doc.physicalRowFormationEvidence());
    }

    private static Map<String, String> resolveRow(Map<String, String> row, Integer width,
                                                  boolean lineEndsPrinted, Set<Rule> fired) {
        boolean anyBreak = false;
        for (String v : row.values()) {
            if (hasBreak(withControlBreaksAsMarks(v))) { anyBreak = true; break; }
        }
        if (!anyBreak) return row;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> cell : row.entrySet()) {
            String n = withControlBreaksAsMarks(cell.getValue());
            resolved.put(cell.getKey(), !hasBreak(n) ? n
                    : resolveCell(piecesOf(n), printedEndsOf(n), width, lineEndsPrinted, fired));
        }
        return resolved;
    }

    /** A control line break that ends a text run is often followed by a join's own break: that is
     *  one printed line end, so a run of breaks with only blanks between them collapses to one. */
    private static final Pattern REPEATED_BREAKS = Pattern.compile("[\n\u001E](?:[ \t]*[\n\u001E])+[ \t]*");

    private static String withControlBreaksAsMarks(String v) {
        if (v == null || !CONTROL_LINE_BREAK.matcher(v).find()) return v;
        String marked = CONTROL_LINE_BREAK.matcher(v).replaceAll(String.valueOf(PRINTED_END));
        return REPEATED_BREAKS.matcher(marked).replaceAll(m ->
                m.group().indexOf(PRINTED_END) >= 0 ? String.valueOf(PRINTED_END) : String.valueOf(MARK));
    }

    private static boolean hasBreak(String v) {
        return v != null && ANY_BREAK.matcher(v).find();
    }

    private static List<String> piecesOf(String v) {
        return Arrays.asList(ANY_BREAK.split(v, -1));
    }

    /** For each break of {@code v}, in order: whether the text layer printed that line end itself. */
    private static List<Boolean> printedEndsOf(String v) {
        List<Boolean> out = new ArrayList<>();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == PRINTED_END) out.add(true);
            else if (c == MARK) out.add(false);
        }
        return out;
    }

    static String resolveCell(List<String> pieces, Integer width) {
        return resolveCell(pieces, null, width, false, EnumSet.noneOf(Rule.class));
    }

    private static String resolveCell(List<String> pieces, List<Boolean> printedEnds, Integer width,
                                      boolean lineEndsPrinted, Set<Rule> fired) {
        List<Boundary> boundaries = width == null ? null : boundaries(trimmedLengths(pieces), width);
        StringBuilder out = new StringBuilder(pieces.get(0));
        for (int i = 1; i < pieces.size(); i++) {
            String line = pieces.get(i - 1).strip();
            String next = pieces.get(i).strip();
            if (lineEndsPrinted && printedEnds != null && !printedEnds.get(i - 1)) {
                // A text layer that prints its own line ends, at a line end it did not print: the
                // line was wrapped, and its last character says where (see the class comment).
                // A blank on either side of the break was printed. Measured: a piece joined from
                // another column arrives with its leading blank, and gluing it built a new token.
                String earlier = pieces.get(i - 1);
                String later = pieces.get(i);
                boolean printedBlank = (!earlier.isEmpty() && Character.isWhitespace(earlier.charAt(earlier.length() - 1)))
                        || (!later.isEmpty() && Character.isWhitespace(later.charAt(0)));
                int end = out.length();
                while (end > 0 && Character.isWhitespace(out.charAt(end - 1))) end--;
                out.setLength(end);
                if (!printedBlank && !line.isEmpty() && !next.isEmpty()) {
                    Rule rule = handleOrSeparator(line, next);
                    fired.add(rule != null ? rule : Rule.WITHOUT_PRINTED_SPACE);
                } else {
                    out.append(' ');
                }
                out.append(pieces.get(i).stripLeading());
                continue;
            }
            Rule rule = null;
            if (boundaries == null) {
                rule = evidenceRule(line, next);
            } else if (boundaries.get(i - 1) == Boundary.FULL_LINE) {
                rule = evidenceRule(line, next);
                if (rule == null && glueAtFullLine(line, next)) rule = Rule.CHARACTER_WIDTH;
            }
            // A break the rebuilt lines place inside a line, or at a line one short of the width, was
            // printed as a space; no rule overrides it except an IFSC, which is never printed with one.
            if (rule == null && boundaries != null && splitsAnIfsc(line, next)) rule = Rule.IFSC;
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
        Rule rule = handleOrSeparator(line, next);
        if (rule != null) return rule;
        return splitsAnIfsc(line, next) ? Rule.IFSC : null;
    }

    private static Rule handleOrSeparator(String line, String next) {
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

    /** See the IFSC rule in the class comment. */
    static boolean splitsAnIfsc(String line, String next) {
        if (line.isEmpty() || next.isEmpty()) return false;
        int tailStart = fieldStart(line);
        if (tailStart < 0) return false;
        String tail = line.substring(tailStart);
        char before = tailStart == 0 ? 0 : line.charAt(tailStart - 1);
        if (IFSC.matcher(tail).matches()) {
            // "...-ABCD0XXXXXX" | "-9000..." : the field already ended; the next line carries on with
            // the separator that opened it.
            return before != 0 && next.charAt(0) == before;
        }
        int headEnd = fieldEnd(next);
        if (headEnd <= 0) return false;
        if (!IFSC.matcher(tail + next.substring(0, headEnd)).matches()) return false;
        return headEnd == next.length() || IFSC_FIELD_SEPARATORS.indexOf(next.charAt(headEnd)) >= 0;
    }

    /** Where the last field of {@code line} starts, or -1 when the character before it is neither the
     *  line's start nor a field separator. */
    private static int fieldStart(String line) {
        int i = line.length();
        while (i > 0 && Character.isLetterOrDigit(line.charAt(i - 1))) i--;
        if (i == line.length()) return -1;
        return i == 0 || IFSC_FIELD_SEPARATORS.indexOf(line.charAt(i - 1)) >= 0 ? i : -1;
    }

    private static int fieldEnd(String next) {
        int i = 0;
        while (i < next.length() && Character.isLetterOrDigit(next.charAt(i))) i++;
        return i;
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
