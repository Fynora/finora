package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line breaks inside a wrapped narration, kept through row formation and resolved once per document.
 *
 * <p>Banks wrap a narration by width, and some wrap at a fixed character count straight through a
 * UPI id, an IFSC or a UTR. Joining the fragments with a hard space at merge time throws away the one
 * fact that decides whether the break was a real space: where the printed line ended. The join sites
 * in {@link PdfTableLocator} write {@link #MARK} instead, and {@link #resolveAll} decides each break
 * once every row of the document is known.
 */
final class NarrationLineBreaks {

    static final char MARK = '\n';

    private NarrationLineBreaks() {}

    /** {@code earlier} was printed on the line above {@code later}. */
    static String joinLines(String earlier, String later) {
        return earlier + MARK + later;
    }

    static PdfTableLocator.LocatedDocument resolveAll(PdfTableLocator.LocatedDocument doc, DocumentContext ctx) {
        List<PdfTableLocator.LocatedSection> sections = new ArrayList<>(doc.sections().size());
        for (PdfTableLocator.LocatedSection section : doc.sections()) {
            List<Map<String, String>> rows = new ArrayList<>(section.rows().size());
            for (Map<String, String> row : section.rows()) {
                rows.add(resolveRow(row));
            }
            sections.add(new PdfTableLocator.LocatedSection(section.auxiliaryText(), rows, section.evidence()));
        }
        return new PdfTableLocator.LocatedDocument(sections, doc.physicalRowFormationEvidence());
    }

    private static Map<String, String> resolveRow(Map<String, String> row) {
        boolean anyBreak = false;
        for (String v : row.values()) {
            if (v != null && v.indexOf(MARK) >= 0) { anyBreak = true; break; }
        }
        if (!anyBreak) return row;
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> cell : row.entrySet()) {
            String v = cell.getValue();
            resolved.put(cell.getKey(), v == null || v.indexOf(MARK) < 0 ? v : v.replace(MARK, ' '));
        }
        return resolved;
    }
}
