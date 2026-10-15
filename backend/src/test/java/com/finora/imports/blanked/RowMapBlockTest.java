package com.finora.imports.blanked;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.RowLines;
import com.finora.imports.pdf.trace.TraceFormat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowMapBlockTest {

    private static RowLines lines(RowLines.Line... lines) {
        return new RowLines(List.of(lines));
    }

    private static final Map<Integer, Map<Integer, RowLines>> MAP = Map.of(
            0, Map.of(
                    11, lines(new RowLines.Line(2, 40120), new RowLines.Line(2, 41280), new RowLines.Line(2, 42440)),
                    0, lines(new RowLines.Line(0, 10000))),
            1, Map.of(3, lines(new RowLines.Line(4, 79905), new RowLines.Line(5, 8000))));

    @Test
    void formatsRowsFromOneAsThePanelNamesThem() {
        assertThat(RowMapBlock.format(MAP)).isEqualTo("""
                # rowmap v1
                # rowmap section 1 row 1: p0 100.00
                # rowmap section 1 row 12: p2 401.20 412.80 424.40
                # rowmap section 2 row 4: p4 799.05 p5 80.00
                """);
    }

    @Test
    void formatsAndParsesBackExactly() {
        String trace = "# finora-pdf-trace v4\n0\t10.00\t100.00\t5.00\tXxxx\n" + RowMapBlock.format(MAP);
        assertThat(RowMapBlock.parse(trace)).isEqualTo(MAP);
    }

    @Test
    void aRowWithNoLinesRoundTrips() {
        Map<Integer, Map<Integer, RowLines>> map = Map.of(0, Map.of(0, RowLines.NONE));
        assertThat(RowMapBlock.format(map)).isEqualTo("# rowmap v1\n# rowmap section 1 row 1:\n");
        assertThat(RowMapBlock.parse(RowMapBlock.format(map))).isEqualTo(map);
    }

    @Test
    void aTraceWithoutABlockParsesToEmpty() {
        assertThat(RowMapBlock.parse("# finora-pdf-trace v4\n0\t10.00\t100.00\t5.00\tXxxx\n")).isEmpty();
        assertThat(RowMapBlock.format(Map.of())).isEmpty();
    }

    @Test
    void theBlockIsIgnoredWhenTheTraceIsReadAsRuns() {
        String trace = TraceFormat.format(List.of(new PositionedText("Xxxx", 10f, 100f, 0, 5f)));
        assertThat(TraceFormat.parse(trace + RowMapBlock.format(MAP))).isEqualTo(TraceFormat.parse(trace));
    }

    @Test
    void aPositionAboveThePageTopRoundTrips() {
        Map<Integer, Map<Integer, RowLines>> map = Map.of(0, Map.of(0, lines(new RowLines.Line(0, -150),
                new RowLines.Line(0, 5)), 1, lines(new RowLines.Line(1, 2_000_000_000))));
        assertThat(RowMapBlock.format(map)).isEqualTo("""
                # rowmap v1
                # rowmap section 1 row 1: p0 -1.50 0.05
                # rowmap section 1 row 2: p1 20000000.00
                """);
        assertThat(RowMapBlock.parse(RowMapBlock.format(map))).isEqualTo(map);
    }
}
