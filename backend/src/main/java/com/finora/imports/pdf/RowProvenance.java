package com.finora.imports.pdf;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which printed lines each row {@code Map} of one {@link PdfTableLocator#locateAll} call was built
 * from. Keyed by identity, not equality: two rows with equal cells are still two rows, and the row
 * maps themselves never carry the record, so nothing that reads a row's cells can see it.
 */
final class RowProvenance {

    private final Map<Map<String, String>, RowLines> lines = new IdentityHashMap<>();

    /** The row was bucketed (wholly or partly) from this physical row. */
    void record(Map<String, String> row, List<PositionedText> physicalRow) {
        add(row, RowLines.of(physicalRow));
    }

    /** {@code source} was merged into {@code target}: the target now also stands on its lines. */
    void merge(Map<String, String> target, Map<String, String> source) {
        if (target == source) return;
        add(target, of(source));
    }

    /** {@code to} replaces {@code from} as the same row. */
    void transfer(Map<String, String> from, Map<String, String> to) {
        if (from == to) return;
        RowLines known = lines.get(from);
        if (known != null) add(to, known);
    }

    /** The row's lines, or {@link RowLines#NONE} when this table never heard of it. */
    RowLines of(Map<String, String> row) {
        return lines.getOrDefault(row, RowLines.NONE);
    }

    private void add(Map<String, String> row, RowLines more) {
        if (more.isEmpty()) return;
        lines.merge(row, more, RowLines::union);
    }
}
