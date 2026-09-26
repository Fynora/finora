package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NarrationLineBreaksTest {

    private static PdfTableLocator.LocatedDocument docWith(String description) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Date", "01/07/2026");
        row.put("Narration", description);
        row.put("Amount", "10.00");
        return new PdfTableLocator.LocatedDocument(
                List.of(new PdfTableLocator.LocatedSection(List.of(), List.of(row), null)), null);
    }

    private static String narrationOf(PdfTableLocator.LocatedDocument doc) {
        return doc.sections().get(0).rows().get(0).get("Narration");
    }

    @Test
    void joinLines_keepsTheBreakForTheResolver() {
        assertThat(NarrationLineBreaks.joinLines("UPI-SAMPLE", "STORE")).isEqualTo("UPI-SAMPLE\nSTORE");
    }

    @Test
    void resolveAll_withNoEvidence_joinsWithOneSpace() {
        // Exactly the pre-existing join: the break becomes one space and nothing around it is touched.
        assertThat(narrationOf(NarrationLineBreaks.resolveAll(docWith("SALARY FROM\nSAMPLE EMPLOYER"), null)))
                .isEqualTo("SALARY FROM SAMPLE EMPLOYER");
    }

    @Test
    void resolveAll_leavesCellsWithoutABreakAlone() {
        var out = NarrationLineBreaks.resolveAll(docWith("SAMPLE"), null);
        assertThat(out.sections().get(0).rows().get(0))
                .containsEntry("Date", "01/07/2026").containsEntry("Narration", "SAMPLE").containsEntry("Amount", "10.00");
    }
}
