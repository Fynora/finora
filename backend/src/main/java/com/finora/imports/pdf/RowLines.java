package com.finora.imports.pdf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The printed lines one transaction row was read from: page and y only, never text (see
 * docs/superpowers/specs/2026-10-15-row-map-design.md).
 *
 * <p>y is held in hundredths of a point, derived from exactly the {@code "%.2f"} a trace writes
 * (see {@code TraceFormat}). A reading of the real runs and a reading of a blanked copy, whose runs
 * were parsed back from that text, therefore name the same line with the same value.
 */
public record RowLines(List<Line> lines) {

    public static final RowLines NONE = new RowLines(List.of());

    /** One printed line: the page index as a trace's page column writes it, and y in hundredths. */
    public record Line(int page, int yHundredths) implements Comparable<Line> {
        @Override
        public int compareTo(Line o) {
            return page != o.page ? Integer.compare(page, o.page) : Integer.compare(yHundredths, o.yHundredths);
        }
    }

    public RowLines {
        lines = List.copyOf(lines);
    }

    /** One physical row is one line, at its topmost run. */
    public static RowLines of(List<PositionedText> physicalRow) {
        if (physicalRow == null || physicalRow.isEmpty()) return NONE;
        PositionedText top = physicalRow.stream()
                .min(Comparator.comparingDouble(PositionedText::y)).orElseThrow();
        return new RowLines(List.of(new Line(top.pageIndex(), hundredths(top.y()))));
    }

    /** The y a trace would print, as an integer number of hundredths. */
    static int hundredths(float y) {
        return new BigDecimal(String.format(Locale.ROOT, "%.2f", y)).movePointRight(2).intValueExact();
    }

    /** Both rows' lines, in page-then-y order, each line once. */
    public RowLines union(RowLines other) {
        if (other.lines.isEmpty()) return this;
        if (lines.isEmpty()) return other;
        TreeSet<Line> all = new TreeSet<>(lines);
        all.addAll(other.lines);
        return new RowLines(new ArrayList<>(all));
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
