package com.finora.imports.blanked;

import com.finora.imports.pdf.RowLines;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The row map block a blanked copy ends with: which printed lines each shown row was read from
 * (docs/superpowers/specs/2026-10-15-row-map-design.md). Comment lines, so anything that reads a
 * trace's runs skips them:
 *
 * <pre>
 * # rowmap v1
 * # rowmap section 1 row 12: p2 401.20 412.80 424.40
 * </pre>
 *
 * <p>Sections and rows are numbered from 1, as the admin panel names them ("Account 2",
 * "Row 12"); in memory both are 0-based, as {@link BlankedCopyComparison.Difference} counts them.
 * A page is numbered as the trace's own page column numbers it, and each y is written with two
 * decimals, exactly as that row's run lines write it. Positions only: never any text.
 */
public final class RowMapBlock {

    static final String HEADER = "# rowmap v1";
    private static final Pattern ROW = Pattern.compile("^# rowmap section (\\d+) row (\\d+):(.*)$");
    private static final Pattern PAGE = Pattern.compile("p(\\d+)");

    private RowMapBlock() {}

    /** The block for a reading's row map, keyed 0-based by section then by shown row. Empty when
     *  the map is empty. Ends with a line break. */
    public static String format(Map<Integer, Map<Integer, RowLines>> bySection) {
        if (bySection.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<Integer, Map<Integer, RowLines>> section : new TreeMap<>(bySection).entrySet()) {
            for (Map.Entry<Integer, RowLines> row : new TreeMap<>(section.getValue()).entrySet()) {
                sb.append("# rowmap section ").append(section.getKey() + 1)
                        .append(" row ").append(row.getKey() + 1).append(':');
                Integer page = null;
                for (RowLines.Line line : row.getValue().lines()) {
                    if (page == null || page != line.page()) {
                        page = line.page();
                        sb.append(" p").append(page);
                    }
                    sb.append(' ').append(BigDecimal.valueOf(line.yHundredths(), 2).toPlainString());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Reads a block back out of a whole trace. Empty when the trace has none. */
    public static Map<Integer, Map<Integer, RowLines>> parse(String trace) {
        Map<Integer, Map<Integer, RowLines>> bySection = new TreeMap<>();
        for (String text : trace.lines().toList()) {
            Matcher row = ROW.matcher(text);
            if (!row.matches()) continue;
            int section = Integer.parseInt(row.group(1)) - 1;
            int shownRow = Integer.parseInt(row.group(2)) - 1;
            List<RowLines.Line> lines = new ArrayList<>();
            Integer page = null;
            for (String token : row.group(3).trim().split(" ")) {
                if (token.isEmpty()) continue;
                Matcher pageToken = PAGE.matcher(token);
                if (pageToken.matches()) {
                    page = Integer.parseInt(pageToken.group(1));
                } else if (page != null) {
                    lines.add(new RowLines.Line(page, new BigDecimal(token).movePointRight(2).intValueExact()));
                } else {
                    throw new IllegalArgumentException("a row map position before its page");
                }
            }
            bySection.computeIfAbsent(section, s -> new TreeMap<>()).put(shownRow, new RowLines(lines));
        }
        return bySection;
    }
}
