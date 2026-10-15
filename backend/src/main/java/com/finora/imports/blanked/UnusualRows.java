package com.finora.imports.blanked;

import com.finora.imports.pdf.RowLines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rows of a reading's row map that are worth a look, judged from positions alone
 * (docs/superpowers/specs/2026-10-15-row-map-design.md, "Unusual rows are pointed out"):
 *
 * <ul>
 *   <li>{@link Kind#TWO_PAGES}: the row's lines are on more than one page;</li>
 *   <li>{@link Kind#MANY_LINES}: the row took more than {@value #MANY_LINES_ABOVE} lines;</li>
 *   <li>{@link Kind#FAR_LINE}: two consecutive lines of the row, on one page, are more than
 *       {@value #FAR_GAP_MULTIPLE} times the median gap apart. The median is taken over every
 *       gap between consecutive lines of that section's table on that page, all rows together, and
 *       only when there are at least {@value #MIN_GAPS_TO_JUDGE} such gaps. A swallowed footer or
 *       notice looks like this.</li>
 * </ul>
 *
 * <p>Sections and rows are 0-based, as {@link BlankedCopyComparison.Difference} counts them.
 */
public final class UnusualRows {

    public enum Kind { TWO_PAGES, MANY_LINES, FAR_LINE }

    /** One reason a row is unusual, with the row's line and page counts. */
    public record Flag(int section, int row, Kind kind, int lines, int pages) {}

    static final int MANY_LINES_ABOVE = 4;
    static final int FAR_GAP_MULTIPLE = 3;
    static final int MIN_GAPS_TO_JUDGE = 3;

    private UnusualRows() {}

    /** The flags for a row map keyed by section then shown row, ordered by section, row, then kind. */
    public static List<Flag> of(Map<Integer, Map<Integer, RowLines>> bySection) {
        List<Flag> flags = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, RowLines>> section : new TreeMap<>(bySection).entrySet()) {
            Map<Integer, Double> medianGapByPage = medianGapByPage(section.getValue());
            for (Map.Entry<Integer, RowLines> row : new TreeMap<>(section.getValue()).entrySet()) {
                List<RowLines.Line> lines = row.getValue().lines();
                int pages = (int) lines.stream().mapToInt(RowLines.Line::page).distinct().count();
                int s = section.getKey();
                int r = row.getKey();
                if (pages > 1) flags.add(new Flag(s, r, Kind.TWO_PAGES, lines.size(), pages));
                if (lines.size() > MANY_LINES_ABOVE) flags.add(new Flag(s, r, Kind.MANY_LINES, lines.size(), pages));
                if (hasFarLine(lines, medianGapByPage)) flags.add(new Flag(s, r, Kind.FAR_LINE, lines.size(), pages));
            }
        }
        return List.copyOf(flags);
    }

    /** The median gap between consecutive lines on each page, over every row of one section, for
     *  the pages with enough gaps to judge by. */
    private static Map<Integer, Double> medianGapByPage(Map<Integer, RowLines> rows) {
        Map<Integer, TreeSet<Integer>> ysByPage = new TreeMap<>();
        for (RowLines row : rows.values()) {
            for (RowLines.Line line : row.lines()) {
                ysByPage.computeIfAbsent(line.page(), p -> new TreeSet<>()).add(line.yHundredths());
            }
        }
        Map<Integer, Double> medians = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<Integer>> page : ysByPage.entrySet()) {
            // long: two y values in hundredths can be further apart than an int holds.
            List<Long> gaps = new ArrayList<>();
            Integer previous = null;
            for (int y : page.getValue()) {
                if (previous != null) gaps.add((long) y - previous);
                previous = y;
            }
            if (gaps.size() < MIN_GAPS_TO_JUDGE) continue;
            gaps.sort(null);
            int mid = gaps.size() / 2;
            medians.put(page.getKey(), gaps.size() % 2 == 1 ? gaps.get(mid) : (gaps.get(mid - 1) + gaps.get(mid)) / 2.0);
        }
        return medians;
    }

    private static boolean hasFarLine(List<RowLines.Line> unordered, Map<Integer, Double> medianGapByPage) {
        List<RowLines.Line> lines = unordered.stream().sorted().toList();
        for (int i = 1; i < lines.size(); i++) {
            RowLines.Line above = lines.get(i - 1);
            RowLines.Line below = lines.get(i);
            if (above.page() != below.page()) continue;
            Double median = medianGapByPage.get(below.page());
            if (median == null) continue;
            if ((long) below.yHundredths() - above.yHundredths() > FAR_GAP_MULTIPLE * median) return true;
        }
        return false;
    }
}
