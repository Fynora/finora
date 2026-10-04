package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LineGeometry}: what a wrapped line's position on the page says about the break beside it.
 * Positions are the real statements' own, measured from their text runs (HDFC: lines start at
 * x=72.0, words 2.0pt apart, a line after a space starts at 74.0; Canara: lines reach x=286.2).
 * Every value is invented.
 */
class LineGeometryTest {

    private static final char SHORT = LineGeometry.STOPPED_SHORT;
    private static final char INDENT = LineGeometry.INDENTED_ONE_SPACE;

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    /** One narration line of words 2.0pt apart starting at {@code x}, each word 30pt wide. */
    private static List<PositionedText> line(float x, float y, String... words) {
        List<PositionedText> out = new ArrayList<>();
        float at = x;
        for (String w : words) {
            out.add(run(w, at, at + 30f, y));
            at += 32f;
        }
        return out;
    }

    /** Forty full lines at x=72.0 reaching x=230.0: the column's start, space width and reach. */
    private static List<List<PositionedText>> page() {
        List<List<PositionedText>> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(line(72f, 100f + 12f * i, "SAMPLE", "STORE", "PAYMENT", "FROM", "PHONE"));
        return rows;
    }

    private static Map<String, String> cells(String narration) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Date", "01/07/2026");
        cells.put("Narration", narration);
        return cells;
    }

    @Test
    void aLineStartingOneSpaceIn_isMarkedIndented() {
        List<List<PositionedText>> rows = page();
        List<PositionedText> indented = line(74f, 300f, "BILL", "AND");
        rows.add(indented);
        var out = LineGeometry.of(rows).decorate(cells("BILL AND"), indented, c -> true);
        assertThat(out.get("Narration")).startsWith(String.valueOf(INDENT)).contains("BILL AND");
    }

    @Test
    void aLineStartingWhereTheColumnStarts_isNotIndented() {
        List<List<PositionedText>> rows = page();
        List<PositionedText> plain = line(72f, 300f, "SAMPLE", "STORE", "PAYMENT", "FROM", "PHONE");
        rows.add(plain);
        var out = LineGeometry.of(rows).decorate(cells("SAMPLE STORE PAYMENT FROM PHONE"), plain, c -> true);
        assertThat(out.get("Narration")).isEqualTo("SAMPLE STORE PAYMENT FROM PHONE");
    }

    @Test
    void anIndentAsCommonAsTheColumnsStart_isAStartOfItsOwn() {
        // Measured on the real HDFC statements, 74.0 is rare beside 72.0; were it common it would be
        // a column of its own, not a dropped space.
        List<List<PositionedText>> rows = page();
        for (int i = 0; i < 12; i++) rows.add(line(74f, 900f + 12f * i, "OTHER", "COLUMN"));
        List<PositionedText> at74 = line(74f, 600f, "BILL", "AND");
        var out = LineGeometry.of(rows).decorate(cells("BILL AND"), at74, c -> true);
        assertThat(out.get("Narration")).doesNotContain(String.valueOf(INDENT));
    }

    @Test
    void aLineEndingWellShortOfTheReach_isMarkedStoppedShort() {
        List<List<PositionedText>> rows = page();
        List<PositionedText> shortLine = line(72f, 300f, "SAMPLE", "STORE");
        rows.add(shortLine);
        var out = LineGeometry.of(rows).decorate(cells("SAMPLE STORE"), shortLine, c -> true);
        assertThat(out.get("Narration")).endsWith(String.valueOf(SHORT));
    }

    @Test
    void aLineEndingWithinOneGlyphOfTheReach_isNot() {
        List<List<PositionedText>> rows = page();
        // The reach is 230.0; this line ends at 229.0, a fraction of one of its glyphs short.
        List<PositionedText> fullLine = new ArrayList<>(line(72f, 300f, "SAMPLE", "STORE", "PAYMENT", "FROM"));
        fullLine.add(run("PHON", 200f, 229f, 300f));
        var out = LineGeometry.of(rows).decorate(cells("SAMPLE STORE PAYMENT FROM PHON"), fullLine, c -> true);
        assertThat(out.get("Narration")).isEqualTo("SAMPLE STORE PAYMENT FROM PHON");
    }

    @Test
    void aDateOrAnAmount_isNeverMarked() {
        List<List<PositionedText>> rows = page();
        List<PositionedText> dateLine = List.of(run("01/07/2026", 74f, 110f, 300f));
        var out = LineGeometry.of(rows).decorate(cells("01/07/2026"), dateLine, c -> true);
        assertThat(out.get("Narration")).isEqualTo("01/07/2026");
    }

    @Test
    void onlyTheColumnsItIsGiven_areMarked() {
        List<List<PositionedText>> rows = page();
        List<PositionedText> shortLine = line(72f, 300f, "SAMPLE", "STORE");
        var out = LineGeometry.of(rows).decorate(cells("SAMPLE STORE"), shortLine, c -> !c.equals("Narration"));
        assertThat(out.get("Narration")).isEqualTo("SAMPLE STORE");
    }

    @Test
    void withNoCommonStart_nothingIsMarked() {
        List<List<PositionedText>> rows = new ArrayList<>();
        rows.add(line(72f, 100f, "SAMPLE", "STORE"));
        List<PositionedText> shortLine = line(74f, 112f, "BILL");
        var out = LineGeometry.of(rows).decorate(cells("BILL"), shortLine, c -> true);
        assertThat(out.get("Narration")).isEqualTo("BILL");
    }
}
