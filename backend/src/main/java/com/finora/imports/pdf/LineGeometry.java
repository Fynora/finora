package com.finora.imports.pdf;

import com.finora.imports.CsvParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Where a wrapped text line began and ended, read from the page, for {@link NarrationLineBreaks}:
 * whether the break after a line or before the next one was a printed space.
 *
 * <p>A wrapped narration's text says nothing about whether a break fell on a space or inside a
 * word -- the text layer drops the space. Its position on the page does. Measured on the real
 * corpus:
 * <ul>
 *   <li><b>A line that stops short</b> of where the column's lines reach was wrapped between words.
 *       On a real Canara statement, a line ending in a person's first name stops at x=234.6
 *       while its narration lines run to x=286: the surname follows on the next line, after a
 *       space. A line ending at x=284.7 in the first six letters of a payee's name was cut inside
 *       the word. Measured over its breaks: cut lines stop at most 1.67 glyphs short, lines
 *       broken at a space at least 2.07.</li>
 *   <li><b>A line that starts one space to the right</b> of where the column's lines start began
 *       with a space the text layer dropped. The real HDFC statements start their narration lines
 *       at x=72.0, and at exactly 74.0 -- 72.0 plus the 2.0pt the same lines leave between their
 *       words -- after each of the five breaks on one statement whose text continues after a space
 *       (a bill payment's remark, two plain words apart), while the lines after a word cut in two
 *       ("UPIINTEN | T", "SUPERMO | NE") start at 72.0.</li>
 * </ul>
 * A cell carrying either fact gets a mark at that end ({@link #decorate}): {@link #STOPPED_SHORT}
 * or {@link #INDENTED_ONE_SPACE}, control characters no statement prints. {@link
 * NarrationLineBreaks} reads them at each break and removes every one from every cell.
 */
final class LineGeometry {

    static final LineGeometry NONE = new LineGeometry(null, Map.of(), Map.of(), Map.of());
    /** Appended to a cell whose line stopped short. Internal only (see NarrationLineBreaks). */
    static final char STOPPED_SHORT = '\u001C';
    /** Prepended to a cell whose line began one space in. Internal only. */
    static final char INDENTED_ONE_SPACE = '\u001F';

    /** A start position this many lines share is where a column's lines begin. */
    private static final int MIN_LINES_AT_A_START = 10;
    /** A position used this many times or fewer, beside a common start, is an exception to it. */
    private static final double RARE_SHARE = 0.05;
    private static final int MIN_GAPS_FOR_A_SPACE_WIDTH = 30;
    private static final float POSITION_TOLERANCE = 0.15f;
    /**
     * A line ending further than this many of its own average glyphs short of its column's reach
     * stopped short. A line cut by width cannot leave room for the glyph that comes next, so its
     * gap is under one glyph -- a wide one ('W') can be 1.7 average glyphs. Measured on the real
     * Canara statement, where both kinds sit side by side on one fixed-length prefix: every line
     * cut inside a word or a code left at most 1.67 average glyphs ("SHASH | WAT", before a 'W'),
     * and every line wrapped at a space at least 2.07 ("NITIKA | N").
     */
    private static final float SHORT_BY_GLYPHS = 1.85f;

    private final Float spaceWidth;
    /** Frequent line-start x (to 0.1pt) -> how many runs start there. */
    private final Map<Integer, Integer> starts;
    /** Every run-start x (to 0.1pt) -> how many runs start there. */
    private final Map<Integer, Integer> allStarts;
    /** Frequent line-start x (to 0.1pt) -> the furthest any line starting there reaches. */
    private final Map<Integer, Float> reach;

    private LineGeometry(Float spaceWidth, Map<Integer, Integer> starts, Map<Integer, Integer> allStarts,
                         Map<Integer, Float> reach) {
        this.spaceWidth = spaceWidth;
        this.starts = starts;
        this.allStarts = allStarts;
        this.reach = reach;
    }

    static LineGeometry of(List<List<PositionedText>> rows) {
        Map<Integer, Integer> gaps = new HashMap<>();
        Map<Integer, Integer> startCounts = new HashMap<>();
        for (List<PositionedText> row : rows) {
            List<PositionedText> sorted = sorted(row);
            for (int i = 0; i < sorted.size(); i++) {
                startCounts.merge(tenths(sorted.get(i).x()), 1, Integer::sum);
                if (i > 0) {
                    float gap = sorted.get(i).x() - sorted.get(i - 1).endX();
                    if (gap > 0.2f && gap < 8f) gaps.merge(tenths(gap), 1, Integer::sum);
                }
            }
        }
        Float spaceWidth = null;
        int best = 0;
        for (Map.Entry<Integer, Integer> e : gaps.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); spaceWidth = e.getKey() / 10f; }
        }
        if (best < MIN_GAPS_FOR_A_SPACE_WIDTH) spaceWidth = null;

        Map<Integer, Integer> frequent = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : startCounts.entrySet()) {
            if (e.getValue() >= MIN_LINES_AT_A_START) frequent.put(e.getKey(), e.getValue());
        }
        LineGeometry partial = new LineGeometry(spaceWidth, frequent, startCounts, Map.of());
        Map<Integer, Float> reach = new HashMap<>();
        for (List<PositionedText> row : rows) {
            List<PositionedText> sorted = sorted(row);
            for (int i = 0; i < sorted.size(); i++) {
                Integer start = partial.startOf(sorted.get(i).x());
                if (start == null) continue;
                float end = partial.chainEnd(sorted, i);
                reach.merge(start, end, Math::max);
            }
        }
        return new LineGeometry(spaceWidth, frequent, startCounts, reach);
    }

    /**
     * {@code cells} with a blank added at the edge of each text cell whose line, read from {@code
     * runs}, began after a space or stopped short. Only cells {@code textColumn} accepts, and never one
     * holding a date or an amount, are touched; nothing is touched without a measured start and reach.
     */
    Map<String, String> decorate(Map<String, String> cells, List<PositionedText> runs, Predicate<String> textColumn) {
        if (reach.isEmpty() || cells.isEmpty()) return cells;
        Map<String, String> out = null;
        List<PositionedText> sorted = sorted(runs);
        for (Map.Entry<String, String> cell : cells.entrySet()) {
            String value = cell.getValue();
            if (value == null || value.isBlank() || !textColumn.test(cell.getKey())) continue;
            // A date or an amount is never a wrapped line: marked, it would no longer parse.
            if (CsvParser.parseDate(value.strip()) != null || CsvParser.parseNumeric(value.strip()) != null) continue;
            int first = -1;
            for (int i = 0; i < sorted.size(); i++) {
                String t = sorted.get(i).text();
                if (!t.isBlank() && value.startsWith(t.strip())) { first = i; break; }
            }
            if (first < 0) continue;
            PositionedText firstRun = sorted.get(first);
            Integer start = startOneSpaceBefore(firstRun.x());
            boolean blankBefore = start != null;
            if (start == null) start = startOf(firstRun.x());
            if (start == null) continue;
            int last = chainLast(sorted, first);
            float end = sorted.get(last).endX();
            int chars = 0;
            for (int i = first; i <= last; i++) chars += sorted.get(i).text().strip().length();
            float glyph = chars == 0 ? 0 : (end - firstRun.x()) / chars;
            Float columnReach = reach.get(start);
            boolean stoppedShort = glyph > 0 && columnReach != null && columnReach - end > SHORT_BY_GLYPHS * glyph
                    && value.endsWith(sorted.get(last).text().strip());
            if (!blankBefore && !stoppedShort) continue;
            if (out == null) out = new LinkedHashMap<>(cells);
            out.put(cell.getKey(), (blankBefore ? String.valueOf(INDENTED_ONE_SPACE) : "") + value
                    + (stoppedShort ? String.valueOf(STOPPED_SHORT) : ""));
        }
        return out == null ? cells : out;
    }

    /** The frequent start {@code x} sits on, or null. */
    private Integer startOf(float x) {
        int t = tenths(x);
        for (int d = -1; d <= 1; d++) if (starts.containsKey(t + d)) return t + d;
        return null;
    }

    /** The frequent start one measured space to the left of {@code x}, when {@code x} itself is
     *  rare beside it (at most {@value #RARE_SHARE} of that start's lines); else null. */
    private Integer startOneSpaceBefore(float x) {
        if (spaceWidth == null) return null;
        Integer start = startOf(x - spaceWidth);
        if (start == null || Math.abs(start / 10f + spaceWidth - x) > POSITION_TOLERANCE) return null;
        int here = 0;
        for (int d = -1; d <= 1; d++) here += allStarts.getOrDefault(tenths(x) + d, 0);
        return here <= RARE_SHARE * starts.get(start) ? start : null;
    }

    /** Index of the last run of the line that run {@code i} begins: runs whose gap to the previous
     *  is at most a few spaces wide (or 6pt when no space width is measured). */
    private int chainLast(List<PositionedText> sorted, int i) {
        float maxGap = spaceWidth == null ? 6f : Math.max(2.5f * spaceWidth, 3f);
        int j = i;
        while (j + 1 < sorted.size() && sorted.get(j + 1).x() - sorted.get(j).endX() <= maxGap
                && sorted.get(j + 1).x() >= sorted.get(j).endX() - 0.5f) {
            j++;
        }
        return j;
    }

    private float chainEnd(List<PositionedText> sorted, int i) {
        return sorted.get(chainLast(sorted, i)).endX();
    }

    private static List<PositionedText> sorted(List<PositionedText> row) {
        List<PositionedText> sorted = new ArrayList<>(row);
        sorted.sort(Comparator.comparingDouble(PositionedText::x));
        return sorted;
    }

    private static int tenths(float v) {
        return Math.round(v * 10f);
    }
}
