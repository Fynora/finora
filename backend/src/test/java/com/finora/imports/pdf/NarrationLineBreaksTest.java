package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    // ---- G2: a UPI handle split at its '@' (the text layer never prints a space beside '@') ----

    @Test
    void aHandleSplitAtTheAt_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-9000000001@", "okaxis-UPI")).isTrue(); // synthetic-ok
        assertThat(NarrationLineBreaks.glueByEvidence("UPI-SAMPLE-9000000001", "@okaxis-UPI")).isTrue(); // synthetic-ok
    }

    // ---- G3: a field separator ending the line, an identifier starting the next ----

    @Test
    void aSeparatorBeforeAnIdentifier_glues() {
        assertThat(NarrationLineBreaks.glueByEvidence("NEFT-", "100000000001-SAMPLE")).isTrue();
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/DR/100000000001/SAMPLE/UTIB/gpay-", "90000000011@okaxis/")).isTrue(); // synthetic-ok
    }

    @Test
    void aSeparatorBeforeAPlainWord_keepsTheSpace() {
        assertThat(NarrationLineBreaks.glueByEvidence("UPI/100000000001/", "SAMPLE STORE/PAYMENT")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence("1.", "MITC")).isFalse();
        assertThat(NarrationLineBreaks.glueByEvidence(":", "STATEMENT")).isFalse();
    }

    @Test
    void aDateThenATimeOnTheNextLine_keepsTheSpace() {
        assertThat(NarrationLineBreaks.glueByEvidence("NACH SAMPLE/18/07/2026", "15:52:30/SAMPLE")).isFalse();
    }

    // ---- G1: a document that prints its narration in fixed-width lines ----

    /** One wrapped narration: a 40-character line that arrived as two pieces split at a real space,
     *  then two more lines. */
    private static List<String> fortyCharCell(String tail, String nextLine) {
        String first = "UPI-SAMPLE PAYEE";                        // 16
        String second = "SAMPLE-" + tail;                         // 16 + 1 + 23 = 40 when tail is 16
        assertThat(first.length() + 1 + second.length()).isEqualTo(40);
        return List.of(first, second, nextLine, "REF");
    }

    @Test
    void characterWrapWidth_findsTheWidthThatEndsMostLines() {
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < 12; i++) cells.add(fortyCharCell("900000001@okaxis", "-SAMPLE-UPI"));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isEqualTo(40);
    }

    @Test
    void characterWrapWidth_isNullForAWordWrappedDocument() {
        List<List<String>> cells = new ArrayList<>();
        int[][] shapes = {{26, 21, 8}, {17, 28, 22}, {24, 19, 25}, {21, 26, 9}, {28, 17, 20}, {19, 24, 26},
                {25, 22, 14}, {23, 27, 18}, {20, 25, 21}, {27, 18, 23}, {22, 20, 27}, {18, 26, 24}};
        for (int[] s : shapes) cells.add(List.of("X".repeat(s[0]), "X".repeat(s[1]), "X".repeat(s[2])));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isNull();
    }

    @Test
    void characterWrapWidth_needsEnoughWrapsToTrust() {
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < 3; i++) cells.add(fortyCharCell("900000001@okaxis", "-SAMPLE-UPI"));
        assertThat(NarrationLineBreaks.characterWrapWidth(cells)).isNull();
    }

    @Test
    void atAFullLine_aSplitDigitRunGlues_andTheSplitInsideTheLineKeepsItsSpace() {
        List<String> cell = List.of("NEFT CR-HDFC0XXXXXX", "SAMPLE PAYER-SAM1234", "56-SAMPLE");
        assertThat(cell.get(0).length() + 1 + cell.get(1).length()).isEqualTo(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("NEFT CR-HDFC0XXXXXX SAMPLE PAYER-SAM123456-SAMPLE");
    }

    @Test
    void atAFullLine_aFieldSeparatorOnTheNextLineGlues() {
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLESTORE@okaxis-SAMP", "-100000000001-UPI"); // synthetic-ok
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLESTORE@okaxis-SAMP-100000000001-UPI"); // synthetic-ok
    }

    @Test
    void atAFullLine_twoPlainWordsKeepTheSpace() {
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTS", "RECEIVED");
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENTS RECEIVED");
    }

    @Test
    void aLineOneShortOfTheWidth_endedAtASpace() {
        List<String> cell = List.of("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENT", "123456-UPI");
        assertThat(cell.get(0)).hasSize(39);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE STORE-SAMPLEMERCHANT-PAYMENT 123456-UPI");
    }

    @Test
    void withNoWidth_onlyTheEvidenceRulesApply() {
        assertThat(NarrationLineBreaks.resolveCell(List.of("SAMPLE1234", "56"), null)).isEqualTo("SAMPLE1234 56");
        assertThat(NarrationLineBreaks.resolveCell(List.of("SAMPLE-9000000001@", "okaxis"), null)).isEqualTo("SAMPLE-9000000001@okaxis"); // synthetic-ok
    }

    @Test
    void resolveAll_glues_andRecordsTheRuleThatFired() {
        var ctx = new com.finora.imports.DocumentContext("PDF", "test");
        var out = NarrationLineBreaks.resolveAll(docWith("UPI-SAMPLE-9000000001@\nokaxis-UPI"), ctx); // synthetic-ok
        assertThat(narrationOf(out)).isEqualTo("UPI-SAMPLE-9000000001@okaxis-UPI"); // synthetic-ok
        assertThat(ctx.capabilities()).extracting(c -> c.capability()).contains("NARRATION_WRAP_JOINED_AT_HANDLE");
    }

    @Test
    void atAFullLine_anUnfinishedHandleGlues_evenLetterToLetter() {
        List<String> cell = List.of("UPI-SAMPLE", "SAMPLEPAYEENAM-900000001@PTAX", "IS-HDFC0XXXXXX-UPI");
        assertThat(cell.get(0).length() + 1 + cell.get(1).length()).isEqualTo(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("UPI-SAMPLE SAMPLEPAYEENAM-900000001@PTAXIS-HDFC0XXXXXX-UPI");
    }

    @Test
    void aHandleWordBreakInsideALine_keepsItsSpace() {
        List<String> cell = List.of("PAID TO SAMPLE@OK", "AXIS STORE", "NEXT");
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("PAID TO SAMPLE@OK AXIS STORE NEXT");
    }

    @Test
    void atAFullLine_aDateThenATimeKeepsTheSpace() {
        List<String> cell = List.of("NACH SAMPLE PAYMENT RF/SAMPLE/18/07/2026", "15:52:30/SAMPLE");
        assertThat(cell.get(0)).hasSize(40);
        assertThat(NarrationLineBreaks.resolveCell(cell, 40)).isEqualTo("NACH SAMPLE PAYMENT RF/SAMPLE/18/07/2026 15:52:30/SAMPLE");
    }
}
