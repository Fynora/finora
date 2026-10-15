package com.finora.imports.pdf;

import java.util.Map;

/**
 * Receives a reading's row map, one call per staged section that has rows: which printed lines
 * each shown row was read from (see docs/superpowers/specs/2026-10-15-row-map-design.md).
 *
 * <p>Only the blanked copy asks for this. Every import path reads without a sink, so the row map is
 * never kept, staged or stored anywhere.
 */
@FunctionalInterface
public interface RowMapSink {

    /**
     * @param sectionIndex        the staged section's index in the reading's result, 0-based: the
     *                            same index a blanked-copy difference names
     * @param linesByDisplayedRow each shown row's lines, keyed by the row's 0-based position as
     *                            shown (the order of {@code StagedAccountSection.rows()}). A located
     *                            row that did not stage is absent. Empty when the reader produced no
     *                            row map for this section.
     */
    void section(int sectionIndex, Map<Integer, RowLines> linesByDisplayedRow);
}
