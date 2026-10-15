package com.finora.imports.blanked;

import com.finora.imports.blanked.UnusualRows.Flag;
import com.finora.imports.blanked.UnusualRows.Kind;
import com.finora.imports.pdf.RowLines;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class UnusualRowsTest {

    /** A row's lines as page/y pairs, y in points. */
    private static RowLines row(Object... pageThenY) {
        List<RowLines.Line> lines = new ArrayList<>();
        for (int i = 0; i < pageThenY.length; i += 2) {
            lines.add(new RowLines.Line((Integer) pageThenY[i], Math.round(((Number) pageThenY[i + 1]).floatValue() * 100)));
        }
        return new RowLines(lines);
    }

    /** One single-line row per y, on page 0, as rows 0..n-1; then {@code extra} as the next row. */
    private static Map<Integer, Map<Integer, RowLines>> page(RowLines extra, float... singleLineYs) {
        Map<Integer, RowLines> rows = new TreeMap<>();
        for (float y : singleLineYs) rows.put(rows.size(), row(0, y));
        rows.put(rows.size(), extra);
        return Map.of(0, rows);
    }

    @Test
    void aRowOnTwoPagesIsFlagged() {
        assertThat(UnusualRows.of(page(row(0, 700, 1, 80), 100, 114, 128)))
                .containsExactly(new Flag(0, 3, Kind.TWO_PAGES, 2, 2));
    }

    @Test
    void fiveLinesIsManyAndFourIsNot() {
        assertThat(UnusualRows.of(page(row(0, 142, 0, 156, 0, 170, 0, 184), 100, 114, 128))).isEmpty();
        assertThat(UnusualRows.of(page(row(0, 142, 0, 156, 0, 170, 0, 184, 0, 198), 100, 114, 128)))
                .containsExactly(new Flag(0, 3, Kind.MANY_LINES, 5, 1));
    }

    @Test
    void aLineFarBelowTheRestIsAFarLine() {
        // Gaps on the page: 14, 14, 14, 14, then the row's own 60. Median 14, and 60 > 3 x 14.
        assertThat(UnusualRows.of(page(row(0, 156, 0, 216), 100, 114, 128, 142)))
                .containsExactly(new Flag(0, 4, Kind.FAR_LINE, 2, 1));
        // Exactly 3 x the median is not far.
        assertThat(UnusualRows.of(page(row(0, 156, 0, 198), 100, 114, 128, 142))).isEmpty();
        // One hundredth past it is.
        assertThat(UnusualRows.of(page(row(0, 156, 0, 198.01f), 100, 114, 128, 142)))
                .containsExactly(new Flag(0, 4, Kind.FAR_LINE, 2, 1));
    }

    @Test
    void aGapBetweenTwoRowsIsNeverARowsFarLine() {
        // The page's last row sits far below the rest, on its own: no row holds that gap.
        assertThat(UnusualRows.of(page(row(0, 400), 100, 114, 128, 142))).isEmpty();
    }

    @Test
    void tooFewLinesOnAPageJudgesNoFarLine() {
        // Two gaps only (14, then the row's 300): not enough to call anything far.
        assertThat(UnusualRows.of(page(row(0, 114, 0, 414), 100))).isEmpty();
    }

    @Test
    void theMedianIsTakenPerSectionAndPage() {
        // Section 1's tightly set table does not make section 0's ordinary spacing look far.
        Map<Integer, Map<Integer, RowLines>> map = new TreeMap<>();
        map.put(0, Map.of(0, row(0, 100, 0, 140), 1, row(0, 180), 2, row(0, 220), 3, row(0, 260)));
        // Ten lines 2pt apart: pooled with section 0's, the median gap would be 2 and section 0's
        // 40pt row gap would read as far.
        Map<Integer, RowLines> tight = new TreeMap<>();
        for (int i = 0; i < 10; i++) tight.put(i, row(0, 500 + 2 * i));
        map.put(1, tight);
        assertThat(UnusualRows.of(map)).isEmpty();
    }

    @Test
    void aRowCanCarrySeveralFlagsInOrder() {
        RowLines both = row(0, 156, 0, 170, 0, 184, 0, 198, 0, 400, 1, 80);
        Map<Integer, Map<Integer, RowLines>> map = new TreeMap<>(page(both, 100, 114, 128, 142));
        map.put(1, Map.of(0, row(2, 100, 3, 50)));
        assertThat(UnusualRows.of(map)).containsExactly(
                new Flag(0, 4, Kind.TWO_PAGES, 6, 2),
                new Flag(0, 4, Kind.MANY_LINES, 6, 2),
                new Flag(0, 4, Kind.FAR_LINE, 6, 2),
                new Flag(1, 0, Kind.TWO_PAGES, 2, 2));
    }

    @Test
    void anEmptyMapHasNoFlags() {
        assertThat(UnusualRows.of(Map.of())).isEmpty();
        assertThat(UnusualRows.of(Map.of(0, Map.of(0, RowLines.NONE)))).isEmpty();
    }

    @Test
    void aGapTooWideForAnIntIsStillMeasured() {
        // -15,000,000pt to +15,000,000pt is 3,000,000,000 hundredths: past an int. The page's six
        // gaps are four of 14pt and two huge ones, so the median is 14pt.
        Map<Integer, Map<Integer, RowLines>> map = Map.of(0, Map.of(
                0, new RowLines(List.of(new RowLines.Line(0, -1_500_000_000), new RowLines.Line(0, 1_500_000_000))),
                1, row(0, 100), 2, row(0, 114), 3, row(0, 128), 4, row(0, 142), 5, row(0, 156)));
        assertThat(UnusualRows.of(map)).containsExactly(new Flag(0, 0, Kind.FAR_LINE, 2, 1));
    }
}
