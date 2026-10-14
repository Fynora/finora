package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's text can hold a carriage return or a line feed. Up to trace v3 the writer copied both out
 * raw: a line feed split the run across two file lines, and the parser stripped a trailing carriage
 * return as a Windows line ending, so a blanked copy of a statement whose runs end in one read
 * differently from the statement. From trace v4 the writer encodes both as visible symbols, and the
 * parser decodes them only in a v4 file, so every older file still parses exactly as it did.
 */
class TraceFormatLineBreakTest {

    private static final TraceMetadata META = new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION,
            PdfTraceRedactor.REDACTOR_VERSION, "TEST", "2026-10-14", "TraceFormatLineBreakTest",
            List.of(), List.of(), "", List.of());

    @Test
    void aCarriageReturnAndALineFeedInsideARunSurviveTheRoundTrip() {
        List<PositionedText> runs = List.of(
                new PositionedText("Xxxx Xxxxx\r", 10f, 20f, 0, 40f),
                new PositionedText("Xxx\nXxxx", 10f, 30f, 0, 25f),
                new PositionedText("\r\n", 10f, 40f, 1, 0f),
                new PositionedText("1,234.00", 300f, 20f, 0, 30f));

        String written = TraceFormat.format(runs, META);
        List<PositionedText> parsed = TraceFormat.parse(written);

        assertThat(parsed).extracting(PositionedText::text)
                .containsExactly("Xxxx Xxxxx\r", "Xxx\nXxxx", "\r\n", "1,234.00");
        // One file line per run: nothing inside a run breaks the line it is written on.
        assertThat(written.lines().filter(l -> !l.startsWith("#")).count()).isEqualTo(runs.size());
        assertThat(written).doesNotContain("\r");
    }

    @Test
    void aTraceWrittenNowDeclaresV4() {
        String written = TraceFormat.format(List.of(new PositionedText("1.00", 1f, 1f, 0, 1f)), META);
        assertThat(written.lines().findFirst()).contains("# finora-pdf-trace v4");
        assertThat(TraceMetadata.parse(written).traceVersion()).isEqualTo(4);
    }

    /** The symbols mean a line break only in a v4 file: in an older one they are the text itself. */
    @Test
    void anOlderTraceKeepsTheSymbolsAsText() {
        String v3 = "# finora-pdf-trace v3\n# page\tx\ty\twidth\ttext\n0\t1.00\t2.00\t3.00\tA\u240DB\u2424\n";
        assertThat(TraceFormat.parse(v3)).singleElement()
                .extracting(PositionedText::text).isEqualTo("A\u240DB\u2424");
    }

    /** A Windows checkout's line ending is still not part of the text, in a v4 file too. */
    @Test
    void aCrlfLineEndingIsStillStripped() {
        String v4 = "# finora-pdf-trace v4\r\n# page\tx\ty\twidth\ttext\r\n0\t1.00\t2.00\t3.00\tAB\u240D\r\n";
        assertThat(TraceFormat.parse(v4)).singleElement()
                .extracting(PositionedText::text).isEqualTo("AB\r");
    }

    /** Every committed trace parses to exactly the runs the v3 parser gave, frozen below. */
    @Test
    void everyCommittedTraceParsesExactlyAsBefore() {
        List<String> names = PdfTrace.committedTraceNames();
        assertThat(names).hasSize(32);
        for (String name : names) {
            String content = PdfTrace.read(name);
            assertThat(TraceFormat.parse(content)).as(name).isEqualTo(v3Parse(content));
        }
    }

    /** The parser as it was at trace v3, kept verbatim as the reference for older files. */
    private static List<PositionedText> v3Parse(String content) {
        List<PositionedText> runs = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] wide = line.split("\t", 5);
            boolean hasWidth = wide.length == 5 && isNumeric(wide[3]);
            if (hasWidth) {
                runs.add(new PositionedText(strip(wide[4]), Float.parseFloat(wide[1]), Float.parseFloat(wide[2]),
                        Integer.parseInt(wide[0]), Float.parseFloat(wide[3])));
                continue;
            }
            String[] parts = line.split("\t", 4);
            runs.add(new PositionedText(strip(parts[3]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                    Integer.parseInt(parts[0])));
        }
        return runs;
    }

    private static boolean isNumeric(String field) {
        if (field.isEmpty()) return false;
        try {
            Float.parseFloat(field);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String strip(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
