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
 *   <li><b>The document's own words</b> -- at a break between two letters that no rule above
 *       decides, the statement's other narrations are the evidence: a word it prints whole
 *       elsewhere ("PHONE", "PAYMENT") glues the break ("PHO | NE"), and a pair it prints with a
 *       space elsewhere ("FROM PHONE") keeps the space, the more frequent of the two winning. When
 *       it prints neither, two halves it prints as words of their own keep the space. Only then,
 *       and only at a break proven to be a width wrap (a full line of a fixed-width document, or
 *       any break of a document whose breaks are shown to cut through words and identifiers), the
 *       break glues: such a wrap falls inside a word far more often than on a space. Measured on
 *       the real corpus: the HDFC statements printed "PAYMEN T FROM PHONE" and "FROM PH ONE" on
 *       dozens of rows, Canara "PAYME NT" and "R EFUND", and slice "Grocerie s" and "B ANK".</li>
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
    /** {@link LineGeometry}'s marks on a line's edges; internal only, removed from every cell. */
    private static final Pattern GEOMETRY_MARKS = Pattern.compile("[" + LineGeometry.STOPPED_SHORT
            + LineGeometry.INDENTED_ONE_SPACE + "]");

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

    enum Rule { HANDLE, SEPARATOR, IFSC, CODE, CHARACTER_WIDTH, WITHOUT_PRINTED_SPACE, DOCUMENT_WORD, FIELD_WIDTH,
        WIDTH_WRAP }

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
        Vocabulary vocabulary = Vocabulary.of(doc);
        boolean cutsThroughWords = vocabulary.cutsThroughWords(cells);
        // Every cell counts toward a field's width, not only the wrapped ones: a field printed whole on
        // one line is the plainest evidence of how wide it runs.
        List<List<String>> allCells = new ArrayList<>();
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            for (Map<String, String> row : section.rows()) {
                for (String v : row.values()) {
                    if (v != null && !v.isBlank()) allCells.add(piecesOf(withControlBreaksAsMarks(v)));
                }
            }
        }
        FieldWidths fieldWidths = FieldWidths.of(allCells, width);
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
                rows.add(resolveRow(row, width, controlBreaks, vocabulary, cutsThroughWords, fieldWidths, fired));
            }
            List<String> auxiliary = new ArrayList<>(section.auxiliaryText().size());
            for (String line : section.auxiliaryText()) auxiliary.add(withoutGeometryMarks(line));
            sections.add(new PdfTableLocator.LocatedSection(auxiliary, rows, section.evidence()));
        }
        if (ctx != null) {
            if (controlBreaks) ctx.record("NARRATION_CONTROL_CHARACTER_AS_LINE_BREAK");
            if (width != null) ctx.record("NARRATION_CHARACTER_WRAP_WIDTH_DETECTED");
            if (fired.contains(Rule.HANDLE)) ctx.record("NARRATION_WRAP_JOINED_AT_HANDLE");
            if (fired.contains(Rule.SEPARATOR)) ctx.record("NARRATION_WRAP_JOINED_AT_SEPARATOR");
            if (fired.contains(Rule.IFSC)) ctx.record("NARRATION_WRAP_JOINED_AT_IFSC");
            if (fired.contains(Rule.CODE)) ctx.record("NARRATION_WRAP_JOINED_INSIDE_A_CODE");
            if (fired.contains(Rule.DOCUMENT_WORD)) ctx.record("NARRATION_WRAP_JOINED_BY_DOCUMENT_WORD");
            if (fired.contains(Rule.FIELD_WIDTH)) ctx.record("NARRATION_WRAP_JOINED_BY_FIELD_WIDTH");
            if (fired.contains(Rule.WIDTH_WRAP)) ctx.record("NARRATION_WRAP_JOINED_AT_WIDTH_WRAP");
            if (fired.contains(Rule.CHARACTER_WIDTH)) ctx.record("NARRATION_WRAP_JOINED_AT_CHARACTER_WIDTH");
            if (fired.contains(Rule.WITHOUT_PRINTED_SPACE)) ctx.record("NARRATION_WRAP_JOINED_WITHOUT_PRINTED_SPACE");
        }
        return new PdfTableLocator.LocatedDocument(sections, doc.physicalRowFormationEvidence());
    }

    private static Map<String, String> resolveRow(Map<String, String> row, Integer width,
                                                  boolean lineEndsPrinted, Vocabulary vocabulary,
                                                  boolean cutsThroughWords, FieldWidths fieldWidths,
                                                  Set<Rule> fired) {
        boolean anyBreak = false, anyMark = false;
        for (String v : row.values()) {
            if (hasBreak(withControlBreaksAsMarks(v))) anyBreak = true;
            if (v != null && GEOMETRY_MARKS.matcher(v).find()) anyMark = true;
        }
        if (!anyBreak && !anyMark) return row;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> cell : row.entrySet()) {
            String n = withControlBreaksAsMarks(cell.getValue());
            resolved.put(cell.getKey(), withoutGeometryMarks(!hasBreak(n) ? n
                    : resolveCell(piecesOf(n), printedEndsOf(n), width, lineEndsPrinted,
                            vocabulary, cutsThroughWords, fieldWidths, fired)));
        }
        return resolved;
    }

    static String withoutGeometryMarks(String v) {
        return v == null || v.indexOf(LineGeometry.STOPPED_SHORT) < 0 && v.indexOf(LineGeometry.INDENTED_ONE_SPACE) < 0
                ? v : GEOMETRY_MARKS.matcher(v).replaceAll("");
    }

    /**
     * Whether the page shows the break between these two raw pieces fell on a space (see {@link
     * LineGeometry}): the later line began one space in, or -- only in a document with no fixed
     * character width, whose lines are cut by how much fits -- the earlier line stopped short. A
     * fixed-width document cuts by character count, so its lines of narrow characters stop short
     * of the rest while still cut inside a word (measured on the real HDFC statements).
     */
    private static boolean pageShowsASpace(String earlier, String later, Integer width) {
        String e = earlier.strip(), l = later.strip();
        // Between two letters or digits, or before a slash: a line can end short after a separator it
        // broke after ("NEFT- | ..."), but no renderer breaks before a slash, so a short line ending in
        // a letter or digit with the next one opening on "/" ("HI | /PUNB" on the real Canara
        // statement) broke at a space.
        if (e.isEmpty() || l.isEmpty() || !Character.isLetterOrDigit(e.charAt(e.length() - 1))
                || !(Character.isLetterOrDigit(l.charAt(0)) || l.charAt(0) == '/')) {
            return false;
        }
        boolean indented = startsWithMark(later, LineGeometry.INDENTED_ONE_SPACE);
        boolean stoppedShort = width == null && endsWithMark(earlier, LineGeometry.STOPPED_SHORT);
        return indented || stoppedShort;
    }

    private static boolean blankPrintedAt(String earlier, String later) {
        String e = withoutGeometryMarks(earlier), l = withoutGeometryMarks(later);
        return (!e.isEmpty() && Character.isWhitespace(e.charAt(e.length() - 1)))
                || (!l.isEmpty() && Character.isWhitespace(l.charAt(0)));
    }

    private static boolean startsWithMark(String s, char mark) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == mark) return true;
            if (!GEOMETRY_MARKS.matcher(String.valueOf(c)).matches()) return false;
        }
        return false;
    }

    private static boolean endsWithMark(String s, char mark) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == mark) return true;
            if (!GEOMETRY_MARKS.matcher(String.valueOf(c)).matches()) return false;
        }
        return false;
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
        return resolveCell(pieces, null, width, false, Vocabulary.EMPTY, false, FieldWidths.NONE,
                EnumSet.noneOf(Rule.class));
    }

    private static String resolveCell(List<String> pieces, List<Boolean> printedEnds, Integer width,
                                      boolean lineEndsPrinted, Vocabulary vocabulary, boolean cutsThroughWords,
                                      FieldWidths fieldWidths, Set<Rule> fired) {
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
                // LineGeometry's marks are not text: a blank the text layer printed is.
                String earlier = withoutGeometryMarks(pieces.get(i - 1));
                String later = withoutGeometryMarks(pieces.get(i));
                boolean printedBlank = (!earlier.isEmpty() && Character.isWhitespace(earlier.charAt(earlier.length() - 1)))
                        || (!later.isEmpty() && Character.isWhitespace(later.charAt(0)))
                        || (!splitsAnIfsc(line, next) && pageShowsASpace(pieces.get(i - 1), pieces.get(i), width));
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
            if (!splitsAnIfsc(line, next) && pageShowsASpace(pieces.get(i - 1), pieces.get(i), width)) {
                // The page shows a space here (see LineGeometry): no text rule but an IFSC, which is
                // never printed with one, overrides it.
                out.append(' ').append(pieces.get(i));
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
            // The document's words decide only where the width layout leaves it open: a break it
            // places inside a line, or one short of the width, was printed as a space.
            // A blank printed at the break -- ending the line or opening the next -- is a space the
            // text layer kept, or a piece joined from another column: no word evidence glues across it.
            if (rule == null && !blankPrintedAt(pieces.get(i - 1), pieces.get(i))
                    && (boundaries == null || boundaries.get(i - 1) == Boundary.FULL_LINE)) {
                // A width wrap is proven by a full line of a fixed-width document, or by any break of a
                // document with no fixed width whose breaks are shown to cut through words. A cell of a
                // fixed-width document that its width could not lay out proves neither (measured on a
                // real HDFC statement: such cells held "PAYMENT | FROM" and a person's first and last name).
                boolean widthWrap = boundaries == null ? width == null && cutsThroughWords
                        : boundaries.get(i - 1) == Boundary.FULL_LINE;
                // The document's own words first: a word it prints whole glues, a pair it prints apart
                // keeps the space, and nothing below overrides either.
                Boolean joinedByTheDocument = vocabulary.evidence(line, next);
                if (joinedByTheDocument != null) {
                    rule = joinedByTheDocument ? Rule.DOCUMENT_WORD : null;
                } else if (fieldWidths.tooWideWithASpace(pieces, i, width)) {
                    rule = Rule.FIELD_WIDTH;
                } else if (widthWrap && vocabulary.isBetweenTwoWords(line, next)) {
                    rule = Rule.WIDTH_WRAP;
                }
            }
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

    /**
     * The words a document prints, counted where nothing could have cut them: every token of a cell
     * with no break, and every token of a wrapped cell except the two that touch each break. Also
     * every pair of words it prints with one space between them. See "The document's own words" in
     * the class comment.
     */
    static final class Vocabulary {
        static final Vocabulary EMPTY = new Vocabulary(Map.of(), Map.of());
        /** A word: letters and digits with at least one letter. */
        private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
        private static final Pattern SPACED_PAIR = Pattern.compile("(?=(?<![\\p{L}\\p{N}])([\\p{L}\\p{N}]+) ([\\p{L}\\p{N}]+)(?![\\p{L}\\p{N}]))");

        private final Map<String, Integer> words;
        private final Map<String, Integer> spacedPairs;

        private Vocabulary(Map<String, Integer> words, Map<String, Integer> spacedPairs) {
            this.words = words;
            this.spacedPairs = spacedPairs;
        }

        static Vocabulary of(PdfTableLocator.LocatedDocument doc) {
            Map<String, Integer> words = new java.util.HashMap<>();
            Map<String, Integer> pairs = new java.util.HashMap<>();
            for (PdfTableLocator.LocatedSection section : doc.sections()) {
                for (Map<String, String> row : section.rows()) {
                    for (String v : row.values()) {
                        if (v == null || v.isBlank()) continue;
                        List<String> pieces = piecesOf(withControlBreaksAsMarks(v));
                        for (int p = 0; p < pieces.size(); p++) {
                            String piece = pieces.get(p);
                            List<String> tokens = new ArrayList<>();
                            java.util.regex.Matcher m = TOKEN.matcher(piece);
                            while (m.find()) tokens.add(m.group());
                            for (int t = 0; t < tokens.size(); t++) {
                                boolean touchesBreak = (t == 0 && p > 0) || (t == tokens.size() - 1 && p < pieces.size() - 1);
                                if (!touchesBreak) words.merge(key(tokens.get(t)), 1, Integer::sum);
                            }
                            java.util.regex.Matcher pair = SPACED_PAIR.matcher(piece);
                            while (pair.find()) pairs.merge(key(pair.group(1)) + " " + key(pair.group(2)), 1, Integer::sum);
                        }
                    }
                }
            }
            return new Vocabulary(words, pairs);
        }

        private static String key(String token) {
            return token.toUpperCase(java.util.Locale.ROOT);
        }

        private int word(String w) {
            return words.getOrDefault(key(w), 0);
        }

        /**
         * Whether this document cuts its lines wherever the width runs out, words and all: at least
         * {@value #MIN_CUTS} IFSCs cut in two. An IFSC sits between hyphens or slashes, where a
         * renderer that wraps between words breaks instead, so only one that cuts anywhere splits
         * it. Measured: the real slice statement cuts 9; the real Canara statement, which wraps
         * between words and cuts only a token longer than its line, none -- and a word it cuts
         * inside such a token (a payee's name cut after its sixth letter) looks exactly like one it
         * wrapped at a space.
         */
        boolean cutsThroughWords(List<List<String>> cells) {
            int cut = 0;
            for (List<String> cell : cells) {
                for (int i = 1; i < cell.size(); i++) {
                    if (splitsAnIfsc(cell.get(i - 1).strip(), cell.get(i).strip())) cut++;
                }
            }
            return cut >= MIN_CUTS;
        }

        static final int MIN_CUTS = 3;

        /** True: the document prints the joined word more often than the spaced pair. False: the
         *  reverse, or never the joined word but both halves as words. Null: no evidence, or not a
         *  break between two words. */
        Boolean evidence(String line, String next) {
            String a = trailingToken(line), b = leadingToken(next);
            if (a == null || b == null) return null;
            int joined = word(a + b);
            int spacedPair = spacedPairs.getOrDefault(key(a) + " " + key(b), 0);
            if (joined > spacedPair) return true;
            if (spacedPair > joined) return false;
            if (joined == 0 && word(a) > 0 && word(b) > 0) return false;
            return null;
        }

        /** Whether the break falls between two words (runs of letters and digits carrying a letter). */
        boolean isBetweenTwoWords(String line, String next) {
            return trailingToken(line) != null && leadingToken(next) != null;
        }

        /** The word ending {@code line} right at the break, when the break falls between two letters
         *  or digits and that word carries a letter; else null. */
        private static String trailingToken(String line) {
            int i = line.length();
            while (i > 0 && Character.isLetterOrDigit(line.charAt(i - 1))) i--;
            String t = line.substring(i);
            return t.chars().anyMatch(Character::isLetter) ? t : null;
        }

        private static String leadingToken(String next) {
            int i = 0;
            while (i < next.length() && Character.isLetterOrDigit(next.charAt(i))) i++;
            String t = next.substring(0, i);
            return t.chars().anyMatch(Character::isLetter) ? t : null;
        }
    }

    /**
     * The widest each field of a slash-delimited narration is printed, per layout: a field is keyed
     * by the narration's opening rail and its position ("UPI/DR/.../<payee>/..."). Measured
     * only on fields every break of which is decided by the page or by an identifier rule. A break
     * no other evidence decides is glued when keeping its space would make its field wider than
     * every such field: measured on the real Canara statement, whose UPI payee field is never more
     * than 9 characters, and six decided ones are exactly 9 (each decided by where its line
     * stopped), while three payee names cut after their sixth letter are 10 only with the space. A field is only taken to have a width when at least
     * {@value #MIN_FIELDS} decided fields are exactly that wide.
     */
    static final class FieldWidths {
        static final FieldWidths NONE = new FieldWidths(Map.of());
        static final int MIN_FIELDS = 3;

        private final Map<String, Integer> widest;

        private FieldWidths(Map<String, Integer> widest) {
            this.widest = widest;
        }

        static FieldWidths of(List<List<String>> cells, Integer width) {
            Map<String, List<Integer>> lengths = new java.util.HashMap<>();
            for (List<String> cell : cells) {
                StringBuilder text = new StringBuilder();
                boolean undecidedInField = false;
                int slashes = 0;
                String head = headOf(cell);
                if (head == null) continue;
                List<int[]> undecidedFieldIndexes = new ArrayList<>();
                for (int i = 0; i < cell.size(); i++) {
                    String piece = withoutGeometryMarks(cell.get(i));
                    if (i > 0) {
                        String line = withoutGeometryMarks(cell.get(i - 1)).strip();
                        String next = piece.strip();
                        boolean space = !splitsAnIfsc(line, next) && pageShowsASpace(cell.get(i - 1), cell.get(i), width);
                        boolean glue = !space && evidenceRule(line, next) != null;
                        if (!space && !glue) undecidedFieldIndexes.add(new int[]{slashes});
                        int end = text.length();
                        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
                        text.setLength(end);
                        if (space) text.append(' ');
                        piece = piece.stripLeading();
                    }
                    for (int c = 0; c < piece.length(); c++) if (piece.charAt(c) == '/') slashes++;
                    text.append(piece);
                }
                String[] fields = text.toString().split("/", -1);
                for (int f = 2; f < fields.length - 1; f++) {
                    final int index = f;
                    if (undecidedFieldIndexes.stream().anyMatch(u -> u[0] == index)) continue;
                    lengths.computeIfAbsent(head + "#" + f, k -> new ArrayList<>()).add(fields[f].length());
                }
            }
            // A cap is only a cap when the document runs into it: at least MIN_FIELDS decided fields
            // exactly at the widest. Free text has a longest field, not a limit -- the real Standard
            // Chartered statement prints "/<name or handle> <account>/" of any length, and taking its
            // longest as a limit glued real spaces in 152 rows.
            Map<String, Integer> widest = new java.util.HashMap<>();
            for (Map.Entry<String, List<Integer>> e : lengths.entrySet()) {
                int max = e.getValue().stream().mapToInt(Integer::intValue).max().orElse(0);
                long atMax = e.getValue().stream().filter(l -> l == max).count();
                if (atMax >= MIN_FIELDS) widest.put(e.getKey(), max);
            }
            return new FieldWidths(widest);
        }

        /** The rail a slash-delimited narration opens with ("UPI"), or null. Debits and credits share
         *  one layout (on the real Canara statement "UPI/DR/..." and "UPI/CR/..." both print a payee
         *  of at most 9), so the direction is not part of the key. */
        private static String headOf(List<String> cell) {
            String first = withoutGeometryMarks(cell.get(0)).strip();
            String[] fields = first.split("/", -1);
            if (fields.length < 3 || fields[0].isBlank()) return null;
            return fields[0].strip().toUpperCase(java.util.Locale.ROOT);
        }

        /**
         * Whether keeping the space at break {@code i} makes its field wider than every decided one,
         * even read as narrowly as it can be: the field may run over more than two lines ("SAMPLE |
         * PE | /ABCD"), and every other break inside it counts as a space only where the page shows
         * one (see LineGeometry), else as none.
         */
        boolean tooWideWithASpace(List<String> pieces, int i, Integer width) {
            if (widest.isEmpty()) return false;
            String head = headOf(pieces);
            if (head == null) return false;
            int slashes = 0;
            for (int p = 0; p < i; p++) {
                String piece = withoutGeometryMarks(pieces.get(p));
                for (int c = 0; c < piece.length(); c++) if (piece.charAt(c) == '/') slashes++;
            }
            Integer max = widest.get(head + "#" + slashes);
            if (max == null) return false;
            // Left: back from the break to the slash that opens the field.
            int length = 1;
            boolean opened = false;
            for (int p = i - 1; p >= 0 && !opened; p--) {
                String piece = withoutGeometryMarks(pieces.get(p)).strip();
                int slash = piece.lastIndexOf('/');
                length += slash >= 0 ? piece.length() - slash - 1 : piece.length();
                opened = slash >= 0;
                if (!opened && p > 0 && pageShowsASpace(pieces.get(p - 1), pieces.get(p), width)) length++;
            }
            // Right: on from the break to the slash that closes it.
            boolean closed = false;
            for (int p = i; p < pieces.size() && !closed; p++) {
                String piece = withoutGeometryMarks(pieces.get(p));
                piece = p == i ? piece.strip() : piece.stripLeading();
                int slash = piece.indexOf('/');
                String part = slash >= 0 ? piece.substring(0, slash) : piece.stripTrailing();
                length += part.length();
                closed = slash >= 0;
                if (!closed && p + 1 < pieces.size() && pageShowsASpace(pieces.get(p), pieces.get(p + 1), width)) length++;
            }
            return opened && closed && length > max;
        }
    }

    /** G2 and G3: evidence that holds on any document, width or not. */
    static boolean glueByEvidence(String line, String next) {
        return evidenceRule(line, next) != null;
    }

    private static Rule evidenceRule(String line, String next) {
        Rule rule = handleOrSeparator(line, next);
        if (rule != null) return rule;
        if (splitsAnIfsc(line, next)) return Rule.IFSC;
        return splitsACode(line, next) ? Rule.CODE : null;
    }

    /**
     * A reference code cut in two: the run of letters and digits ending the line and the one
     * starting the next both mix letters with digits ("40ABC1234D56 | E7890F12"). Such a code is
     * never printed with a space inside it. Measured on the real Canara statement, which printed
     * its UPI reference ids split this way on dozens of rows.
     */
    static boolean splitsACode(String line, String next) {
        String a = trailingRun(line), b = leadingRun(next);
        // A whole IFSC is a field of its own: the real Standard Chartered statement prints it
        // followed by a space and an account or code inside the same field ("IFSC 0123SL..."), so a
        // break beside one is no evidence of a code cut in two.
        if (IFSC.matcher(a).matches() || IFSC.matcher(b).matches()) return false;
        return mixesLettersAndDigits(a) && mixesLettersAndDigits(b);
    }

    private static boolean mixesLettersAndDigits(String run) {
        return run.chars().anyMatch(Character::isLetter) && run.chars().anyMatch(Character::isDigit);
    }

    private static String trailingRun(String line) {
        int i = line.length();
        while (i > 0 && Character.isLetterOrDigit(line.charAt(i - 1))) i--;
        return line.substring(i);
    }

    private static String leadingRun(String next) {
        int i = 0;
        while (i < next.length() && Character.isLetterOrDigit(next.charAt(i))) i++;
        return next.substring(0, i);
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
