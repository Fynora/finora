package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text format a statement's extracted text layer -- every {@link PositionedText} run with its
 * coordinates -- is written to and read back from. The writing and parsing half of what the test
 * tree calls {@code PdfTrace}; the loading of committed traces from disk stays there.
 *
 * This exists because the bugs worth regression-testing in this pipeline are POSITIONAL. Column
 * anchors that don't line up with their headers, a page banner whose x lands in the date column, a
 * multi-word header arriving as two separate runs: none of that survives being described in prose,
 * and none of it can be reproduced by hand-building a synthetic PDF, because rebuilding the PDF
 * rebuilds the layout and loses the exact fragmentation that caused the bug.
 *
 * A trace is captured from the real file once (see PdfPipelineDiagnostic's capture mode), redacted
 * (see {@link PdfTraceRedactor}), and committed. What lands in the repo is structurally identical
 * to the customer's document and contains none of their data -- which is what makes the Synthetic
 * Fixture Policy affordable to follow rather than a tax people route around.
 *
 * The format is deliberately plain text rather than anything binary or generated: a fixture nobody
 * can read is a fixture nobody will check for PII before committing.
 *
 * <pre>
 * # finora-pdf-trace v1
 * # page &lt;tab&gt; x &lt;tab&gt; y &lt;tab&gt; text
 * 0	35.00	720.50	Txn Date
 * </pre>
 */
public final class TraceFormat {

    private TraceFormat() {}

    private static final String MAGIC = "# finora-pdf-trace v1";

    /** v1 traces carry no metadata; v2 adds a provenance block (see {@link TraceMetadata}) so a
     *  trace states which redactor and allowlist produced it. Both parse identically -- every
     *  metadata line is a comment, which the v1 parser already skipped. */
    private static final String MAGIC_V2 = "# finora-pdf-trace v2";

    /** v3 adds a width column to every run. See {@link TraceMetadata#CURRENT_TRACE_VERSION} for why
     *  a version bump rather than an extra column under v2. */
    private static final String MAGIC_V3 = "# finora-pdf-trace v3";

    /** v4 encodes a carriage return or line feed inside a run's text as {@link #CR_SYMBOL} and
     *  {@link #LF_SYMBOL}. Up to v3 both were written raw: a line feed split a run across two file
     *  lines, and the parser stripped a trailing carriage return as a Windows line ending, so a
     *  statement whose runs end in one read differently once written. */
    private static final String MAGIC_V4 = "# finora-pdf-trace v4";
    private static final Pattern MAGIC_VERSION = Pattern.compile("^# finora-pdf-trace v(\\d+)");

    /** U+240D SYMBOL FOR CARRIAGE RETURN and U+2424 SYMBOL FOR NEWLINE: none of the committed
     *  traces holds either, and they are decoded only in a v4 file, so an older file that did hold
     *  one would still read it as the symbol it is. A v4 run whose real text holds the symbol
     *  itself reads back as a line break; that collision is accepted as rare. */
    static final char CR_SYMBOL = '\u240D';
    static final char LF_SYMBOL = '\u2424';

    /** Backward-compatible: writes a v1 trace with no provenance. Retained only so existing
     *  round-trip tests keep exercising the parser; {@link #format(List, TraceMetadata)} is what
     *  the capture path uses. */
    public static String format(List<PositionedText> runs) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n')
                .append("# page\tx\ty\ttext\n");
        for (PositionedText run : runs) {
            // A tab inside the text would corrupt the column split on the way back in. PDFBox
            // yields tabs as spacing rather than characters, so this is belt-and-braces.
            String text = run.text().replace('\t', ' ');
            sb.append(run.pageIndex()).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.x())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.y())).append('\t')
                    .append(text).append('\n');
        }
        return sb.toString();
    }

    /** A v2 trace: the same run data, preceded by the provenance block that says which redactor and
     *  allowlist produced it and which capability it exists to protect. */
    public static String format(List<PositionedText> runs, TraceMetadata metadata) {
        return MAGIC_V4 + '\n'
                + metadata.toHeaderLines()
                + "# page\tx\ty\twidth\ttext\n"
                + formatRuns(runs);
    }

    private static String formatRuns(List<PositionedText> runs) {
        StringBuilder sb = new StringBuilder();
        for (PositionedText run : runs) {
            String text = run.text().replace('\t', ' ').replace('\r', CR_SYMBOL).replace('\n', LF_SYMBOL);
            sb.append(run.pageIndex()).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.x())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.y())).append('\t')
                    .append(String.format(Locale.ROOT, "%.2f", run.width())).append('\t')
                    .append(text).append('\n');
        }
        return sb.toString();
    }

    /**
     * Reads either row shape.
     *
     * <p>v3 rows are {@code page, x, y, width, text}; v1 and v2 rows are {@code page, x, y, text}.
     * Decided per line by field count rather than by the magic line, so a hand-edited file with a
     * mixed body still parses to something rather than throwing at an unrelated place.
     *
     * <p>A 4-field row yields width 0, which is exactly what it meant before — those runs keep
     * their previous bucketing, and any capability guarded on a measured width stays unreachable
     * on them. That is a real limitation of the older traces, not a defect in this parser, and
     * {@link TraceMetadata#hasNoWidths(List)} is how a caller asks about it -- of the parsed runs,
     * since a file's version stamp and its actual width data are written independently.
     */
    public static List<PositionedText> parse(String content) {
        boolean encoded = encodesLineBreaks(content);
        List<PositionedText> runs = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            // Split limit is one past the last coordinate, so a text field containing tabs (see
            // format()) can never shift the columns.
            String[] wide = line.split("\t", 5);
            boolean hasWidth = wide.length == 5 && isNumeric(wide[3]);
            if (hasWidth) {
                runs.add(new PositionedText(text(wide[4], encoded),
                        Float.parseFloat(wide[1]), Float.parseFloat(wide[2]),
                        Integer.parseInt(wide[0]), Float.parseFloat(wide[3])));
                continue;
            }

            String[] parts = line.split("\t", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("Malformed trace line, expected page/x/y/text or "
                        + "page/x/y/width/text but got " + parts.length + " field(s): " + line);
            }
            runs.add(new PositionedText(text(parts[3], encoded),
                    Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Integer.parseInt(parts[0])));
        }
        return runs;
    }

    /** Whether the file's magic line says v4 or later, the first version that encodes line breaks
     *  inside a run. Older files are read exactly as they always were. */
    private static boolean encodesLineBreaks(String content) {
        Matcher magic = MAGIC_VERSION.matcher(content);
        return magic.find() && Integer.parseInt(magic.group(1)) >= 4;
    }

    private static String text(String field, boolean encoded) {
        String text = stripTrailingCarriageReturn(field);
        return encoded ? text.replace(CR_SYMBOL, '\r').replace(LF_SYMBOL, '\n') : text;
    }

    /** Whether a field is a bare number, which is what separates a v3 width column from the first
     *  tab-delimited chunk of a v1/v2 text field. A text run that happens to be entirely numeric is
     *  the ambiguous case, and it is resolved by the field COUNT: a 5-field v1 row would need three
     *  tabs inside its text, which format() strips on the way out. */
    private static boolean isNumeric(String field) {
        if (field.isEmpty()) return false;
        try {
            Float.parseFloat(field);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Git on Windows checks these out with CRLF; the trailing \r would otherwise become part of
     *  the last column's text and silently break every string comparison in a fixture test. */
    private static String stripTrailingCarriageReturn(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
